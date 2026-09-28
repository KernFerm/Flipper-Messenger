package com.kernferm.flippermessenger

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.*
import android.content.pm.PackageManager
import android.os.*
import android.telephony.SmsManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

@SuppressLint("MissingPermission")
class MessengerService : Service() {
    interface Listener {
        fun onState(text: String)
        fun onDevices(devices: List<BluetoothDevice>)
        fun onEvent(text: String)
    }

    inner class LocalBinder : Binder() {
        fun service() = this@MessengerService
    }

    companion object {
        private val SERVICE = UUID.fromString("8fe5b3d5-2e7f-4a98-2a48-7acc60fe0000")
        private val TX = UUID.fromString("19ed82ae-ed21-4c9d-4145-228e61fe0000")
        private val RX = UUID.fromString("19ed82ae-ed21-4c9d-4145-228e62fe0000")
        private val CCC = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val CHANNEL = "flipper_link"
        private const val NOTIFICATION = 81
        private const val ACTION_SENT = "com.kernferm.flippermessenger.SMS_SENT"
        private const val ACTION_DELIVERED = "com.kernferm.flippermessenger.SMS_DELIVERED"
    }

    private val binder = LocalBinder()
    private val listeners = CopyOnWriteArrayList<Listener>()
    private val devices = linkedMapOf<String, BluetoothDevice>()
    private val writes = ArrayDeque<ByteArray>()
    private lateinit var bluetooth: BluetoothManager
    private lateinit var store: SecureStore
    private var gatt: BluetoothGatt? = null
    private var rx: BluetoothGattCharacteristic? = null
    private var tx: BluetoothGattCharacteristic? = null
    private var writing = false
    private var scanning = false
    private var state = "Disconnected"
    private var key: ByteArray? = null
    private var session = ByteArray(12)
    private var txSequence = 0L
    private var rxSequence = 0L
    private var authenticated = false
    private var connectedDevice: BluetoothDevice? = null
    private var shouldReconnect = true
    private var foregroundActive = false
    private var contactSyncPending = false
    private val reconnectHandler = Handler(Looper.getMainLooper())
    private val partSent = mutableMapOf<Long, BooleanArray>()
    private val partDelivered = mutableMapOf<Long, BooleanArray>()

    override fun onCreate() {
        super.onCreate()
        bluetooth = getSystemService(BluetoothManager::class.java)
        store = SecureStore(this)
        key = store.loadLinkKey()
        createChannel()
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(ACTION_SENT)
            addAction(ACTION_DELIVERED)
        }
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onBind(intent: Intent?) = binder
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        shouldReconnect = true
        if (gatt == null && bluetoothAllowed() && store.selectedDevice() != null) {
            startForeground(NOTIFICATION, notification("Reconnecting to saved Flipper\u2026"))
            foregroundActive = true
            reconnectHandler.post(::reconnectSaved)
        }
        return START_STICKY
    }
    override fun onDestroy() {
        stopScan()
        disconnect()
        unregisterReceiver(receiver)
        super.onDestroy()
    }

    fun addListener(listener: Listener) {
        listeners += listener
        listener.onState(state)
        listener.onDevices(devices.values.toList())
    }
    fun removeListener(listener: Listener) { listeners -= listener }
    private fun reportState(value: String) {
        state = value
        listeners.forEach { it.onState(value) }
        if (foregroundActive) {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(value))
        }
    }
    private fun event(value: String) { listeners.forEach { it.onEvent(value) } }

    private fun has(permission: String) = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    private fun bluetoothAllowed() = Build.VERSION.SDK_INT < 31 ||
        (has(Manifest.permission.BLUETOOTH_SCAN) && has(Manifest.permission.BLUETOOTH_CONNECT))

    fun startScan() {
        if (!bluetoothAllowed()) { reportState("Bluetooth permission required"); return }
        val adapter = bluetooth.adapter ?: run { reportState("Bluetooth unavailable"); return }
        if (!adapter.isEnabled) { reportState("Turn Bluetooth on"); return }
        devices.clear()
        scanning = true
        reportState("Scanning for Flipper Zero…")
        adapter.bluetoothLeScanner?.startScan(scanCallback)
        Handler(Looper.getMainLooper()).postDelayed({ stopScan() }, 12_000)
    }

    fun stopScan() {
        if (!scanning || !bluetoothAllowed()) return
        bluetooth.adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        scanning = false
        if (gatt == null) reportState(if (devices.isEmpty()) "No Flipper found" else "Choose a Flipper")
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.scanRecord?.deviceName ?: result.device.name ?: ""
            if (name.contains("Flipper", true)) {
                devices[result.device.address] = result.device
                listeners.forEach { it.onDevices(devices.values.toList()) }
            }
        }
        override fun onScanFailed(errorCode: Int) { scanning = false; reportState("BLE scan failed ($errorCode)") }
    }

    fun connect(device: BluetoothDevice) {
        stopScan()
        if (!bluetoothAllowed()) return
        connectedDevice = device
        shouldReconnect = true
        store.saveDevice(device.address)
        if (device.bondState == BluetoothDevice.BOND_NONE) {
            reportState("Confirm the Bluetooth PIN on both devices")
            if (!device.createBond()) reportState("Could not start bonding")
        } else connectGatt(device)
    }

    fun reconnectSaved() {
        if (!bluetoothAllowed()) return
        val address = store.selectedDevice() ?: return
        runCatching { bluetooth.adapter.getRemoteDevice(address) }.getOrNull()?.let(::connect)
    }

    private fun connectGatt(device: BluetoothDevice) {
        gatt?.close()
        reportState("Connecting to ${device.name ?: "Flipper"}…")
        gatt = device.connectGatt(this, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        shouldReconnect = false
        closeLink()
        reportState("Disconnected")
    }

    private fun closeLink() {
        authenticated = false
        gatt?.disconnect(); gatt?.close(); gatt = null
        rx = null; tx = null; writing = false; writes.clear()
        stopForeground(STOP_FOREGROUND_REMOVE)
        foregroundActive = false
    }

    private fun linkLost() {
        closeLink()
        reportState("Disconnected; reconnecting…")
        if (shouldReconnect) reconnectHandler.postDelayed({ reconnectSaved() }, 2_000)
    }

    fun provision() {
        if (rx == null) { event("Connect first, then enable Authorize phone on the Flipper."); return }
        val generated = ByteArray(32).also(SecureRandom()::nextBytes)
        store.saveLinkKey(generated)
        key = generated
        queue(Protocol.encode(Protocol.Message(Protocol.PROVISION_KEY, payload = generated), null))
        event("Authorization key sent. The Flipper must be in its 60-second authorization window.")
    }

    fun forget() {
        store.clearLink(); key?.fill(0); key = null; authenticated = false
        connectedDevice?.let { runCatching { it.javaClass.getMethod("removeBond").invoke(it) } }
        disconnect()
        event("Local authorization removed. Use Forget phone on the Flipper too.")
    }

    fun syncContacts(contacts: List<Pair<String, String>>) {
        if (!authenticated) { event("Authorize the connected Flipper first."); return }
        if (contactSyncPending) { event("A contact sync is already in progress."); return }
        val valid = contacts.take(50).mapNotNull { (rawName, rawPhone) ->
            val name = utf8Prefix(rawName, 31)
            val phoneBytes = rawPhone.toByteArray()
            if (phoneBytes.size in 1..23) name to phoneBytes else null
        }
        contactSyncPending = true
        send(Protocol.CONTACT_BEGIN, payload = byteArrayOf(valid.size.toByte()))
        valid.forEach { (name, phoneBytes) ->
            val payload = byteArrayOf(name.size.toByte(), phoneBytes.size.toByte()) + name + phoneBytes
            send(Protocol.CONTACT_ENTRY, payload = payload)
        }
        send(Protocol.CONTACT_END)
        event("Sending ${valid.size} contacts; waiting for Flipper storage confirmation\u2026")
    }

    fun forwardIncoming(sender: String, body: String) {
        if (!authenticated) return
        val senderBytes = utf8Prefix(sender, 23)
        val bodyBytes = utf8Prefix(body, 320)
        if (senderBytes.isEmpty() || bodyBytes.isEmpty()) return
        val payload = byteArrayOf(
            senderBytes.size.toByte(),
            bodyBytes.size.toByte(),
            (bodyBytes.size ushr 8).toByte(),
        ) + senderBytes + bodyBytes
        send(Protocol.INCOMING_SMS, payload = payload)
        event("New SMS forwarded to Flipper")
    }

    private fun utf8Prefix(value: String, maximumBytes: Int): ByteArray {
        val output = ArrayList<Byte>(maximumBytes)
        var offset = 0
        while (offset < value.length) {
            val codePoint = value.codePointAt(offset)
            val encoded = String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8)
            if (output.size + encoded.size > maximumBytes) break
            encoded.forEach(output::add)
            offset += Character.charCount(codePoint)
        }
        return output.toByteArray()
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                reportState("Connected; discovering service…")
                g.requestMtu(517)
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) linkLost()
            else reportState("Connection failed ($status)")
        }
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val service = g.getService(SERVICE)
            rx = service?.getCharacteristic(RX)
            tx = service?.getCharacteristic(TX)
            if (status != BluetoothGatt.GATT_SUCCESS || rx == null || tx == null) {
                reportState("Flipper Messenger BLE service not found"); return
            }
            g.setCharacteristicNotification(tx, true)
            val descriptor = tx?.getDescriptor(CCC) ?: run { reportState("BLE indication descriptor missing"); return }
            if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)
            else {
                @Suppress("DEPRECATION")
                descriptor.value = BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                @Suppress("DEPRECATION")
                g.writeDescriptor(descriptor)
            }
        }
        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                startForeground(NOTIFICATION, notification("Connected; starting secure session\u2026"))
                foregroundActive = true
                reportState("Connected; starting secure session…")
                queue(Protocol.encode(Protocol.Message(Protocol.HELLO), null))
            } else reportState("Could not enable BLE indications ($status)")
        }
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION") receive(characteristic.value?.clone() ?: return)
        }
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            receive(value.clone())
        }
        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            synchronized(writes) { writing = false }
            if (status != BluetoothGatt.GATT_SUCCESS) reportState("BLE write failed ($status)")
            writeNext()
        }
    }

    private fun receive(frame: ByteArray) {
        try {
            val message = Protocol.decode(frame, key, rxSequence)
            if (message.encrypted) {
                if (!message.session.contentEquals(session)) return
                rxSequence = message.sequence
            }
            when (message.type) {
                Protocol.HELLO_REPLY -> {
                    require(message.payload.size == 33)
                    session = message.payload.copyOfRange(1, 13)
                    val challenge = message.payload.copyOfRange(17, 33)
                    txSequence = 0; rxSequence = 0; authenticated = false
                    if (message.payload[0].toInt() == 0 || key == null) {
                        reportState("Connected; authorization required")
                    } else {
                        send(Protocol.AUTH_RESPONSE, payload = challenge)
                        reportState("Authenticating…")
                    }
                }
                Protocol.ACCEPTED -> if (!authenticated && message.requestId == 0L) {
                    authenticated = true
                    reportState("Authorized and ready")
                    event("Secure session authenticated.")
                }
                Protocol.SEND_SMS -> processSms(message)
                Protocol.CONTACT_RESULT -> {
                    require(message.payload.size == 1)
                    contactSyncPending = false
                    event("${message.payload[0].toInt() and 0xff} contacts saved on Flipper microSD.")
                }
                Protocol.PING -> send(Protocol.PONG, message.requestId)
                Protocol.ERROR -> {
                    contactSyncPending = false
                    val detail = if (message.payload.size >= 2) {
                        val length = (message.payload[1].toInt() and 0xff).coerceAtMost(message.payload.size - 2)
                        message.payload.copyOfRange(2, 2 + length).toString(Charsets.UTF_8)
                    } else "Protocol error"
                    event("Flipper error: $detail")
                }
            }
        } catch (e: Exception) { reportState("Rejected invalid/authentication frame") }
    }

    @Synchronized
    private fun send(type: Int, requestId: Long = 0, payload: ByteArray = byteArrayOf()) {
        val linkKey = key ?: return
        val message = Protocol.Message(type, requestId, ++txSequence, session, payload, true)
        queue(Protocol.encode(message, linkKey))
    }

    private fun queue(frame: ByteArray) {
        synchronized(writes) { writes.addLast(frame) }
        writeNext()
    }

    private fun writeNext() {
        val characteristic = rx ?: return
        val frame = synchronized(writes) {
            if (writing || writes.isEmpty()) return
            writing = true
            writes.removeFirst()
        }
        val currentGatt = gatt ?: return
        if (Build.VERSION.SDK_INT >= 33) {
            val result = currentGatt.writeCharacteristic(characteristic, frame, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            if (result != BluetoothStatusCodes.SUCCESS) { synchronized(writes) { writing = false }; reportState("BLE queue rejected ($result)") }
        } else {
            @Suppress("DEPRECATION")
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            characteristic.value = frame
            @Suppress("DEPRECATION") if (!currentGatt.writeCharacteristic(characteristic)) { synchronized(writes) { writing = false }; reportState("BLE queue rejected") }
        }
    }

    private fun processSms(message: Protocol.Message) {
        if (!authenticated || message.payload.size < 3) return
        val phoneLength = message.payload[0].toInt() and 0xff
        val bodyLength = (message.payload[1].toInt() and 0xff) or ((message.payload[2].toInt() and 0xff) shl 8)
        if (phoneLength !in 1..23 || bodyLength !in 1..320 || 3 + phoneLength + bodyLength != message.payload.size) {
            failure(message.requestId, "Malformed SMS request"); return
        }
        store.requestStatus(message.requestId)?.let { status -> send(status, message.requestId); return }
        if (!has(Manifest.permission.SEND_SMS)) { failure(message.requestId, "SMS permission revoked"); return }
        val phone = message.payload.copyOfRange(3, 3 + phoneLength).toString(Charsets.UTF_8)
        val body = message.payload.copyOfRange(3 + phoneLength, message.payload.size).toString(Charsets.UTF_8)
        if (!phone.matches(Regex("^\\+?[0-9 ()-]{3,23}$"))) { failure(message.requestId, "Invalid recipient"); return }

        val subscription = store.selectedSubscription()
        val baseManager = getSystemService(SmsManager::class.java)
        val manager = if (subscription < 0) {
            baseManager
        } else if (Build.VERSION.SDK_INT >= 31) {
            baseManager.createForSubscriptionId(subscription)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getSmsManagerForSubscriptionId(subscription)
        }
        val parts = manager.divideMessage(body)
        if (parts.isEmpty()) { failure(message.requestId, "Empty SMS"); return }
        if (!store.saveRequestStatus(message.requestId, Protocol.ACCEPTED)) {
            send(Protocol.SMS_FAILED, message.requestId, "Could not persist duplicate protection".toByteArray())
            event("SMS not sent: duplicate-protection storage failed")
            return
        }
        send(Protocol.ACCEPTED, message.requestId)
        partSent[message.requestId] = BooleanArray(parts.size)
        partDelivered[message.requestId] = BooleanArray(parts.size)
        val sent = ArrayList<PendingIntent>(parts.size)
        val delivered = ArrayList<PendingIntent>(parts.size)
        parts.indices.forEach { part ->
            sent += statusIntent(ACTION_SENT, message.requestId, part, parts.size)
            delivered += statusIntent(ACTION_DELIVERED, message.requestId, part, parts.size)
        }
        try {
            manager.sendMultipartTextMessage(phone, null, parts, sent, delivered)
            event("SMS request accepted by Android for $phone")
        } catch (e: Exception) { failure(message.requestId, e.message ?: "SMS send failed") }
    }

    private fun statusIntent(action: String, request: Long, part: Int, count: Int): PendingIntent {
        val intent = Intent(action).setPackage(packageName)
            .putExtra("request", request).putExtra("part", part).putExtra("count", count)
        val requestCode = (request xor (request ushr 32)).toInt() + part + if (action == ACTION_DELIVERED) 10_000 else 0
        return PendingIntent.getBroadcast(this, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun failure(request: Long, reason: String) {
        store.saveRequestStatus(request, Protocol.SMS_FAILED)
        send(Protocol.SMS_FAILED, request, reason.toByteArray().copyOf(80))
        event("SMS failed: $reason")
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == BluetoothDevice.ACTION_BOND_STATE_CHANGED) {
                val device = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    else @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                device?.let {
                    if (it.address == connectedDevice?.address && it.bondState == BluetoothDevice.BOND_BONDED) connectGatt(it)
                }
                return
            }
            val request = intent.getLongExtra("request", 0)
            val part = intent.getIntExtra("part", -1)
            val count = intent.getIntExtra("count", 0)
            if (request == 0L || part !in 0 until count) return
            if (resultCode != Activity.RESULT_OK) { failure(request, "Carrier/SMS result $resultCode"); return }
            val map = if (intent.action == ACTION_SENT) partSent else partDelivered
            val values = map.getOrPut(request) { BooleanArray(count) }
            values[part] = true
            if (values.all { it }) {
                val status = if (intent.action == ACTION_SENT) Protocol.SMS_SENT else Protocol.SMS_DELIVERED
                store.saveRequestStatus(request, status)
                send(status, request)
                map.remove(request)
                event(if (status == Protocol.SMS_SENT) "SMS sent" else "SMS delivered")
            }
        }
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW))
    }
    private fun notification(text: String = getString(R.string.service_notification)): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(getString(R.string.app_name)).setContentText(text)
            .setContentIntent(open).setOngoing(true).build()
    }
}

