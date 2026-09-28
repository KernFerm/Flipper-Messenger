package com.kernferm.flippermessenger

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.content.res.ColorStateList
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

@SuppressLint("MissingPermission")
class MainActivity : AppCompatActivity(), MessengerService.Listener {
    private var service: MessengerService? = null
    private var bound = false
    private lateinit var status: TextView
    private lateinit var permissionState: TextView
    private lateinit var devicesButton: Button
    private lateinit var simSpinner: Spinner
    private var devices: List<BluetoothDevice> = emptyList()
    private var subscriptions: List<SubscriptionInfo> = emptyList()
    private val contactPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val names = result.data?.getStringArrayListExtra("names") ?: arrayListOf()
            val phones = result.data?.getStringArrayListExtra("phones") ?: arrayListOf()
            service?.syncContacts(names.zip(phones))
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = (binder as MessengerService.LocalBinder).service()
            service?.addListener(this@MainActivity)
            bound = true
            service?.reconnectSaved()
        }
        override fun onServiceDisconnected(name: ComponentName) { bound = false; service = null }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.app_name)
        setContentView(buildUi())
        startService(Intent(this, MessengerService::class.java))
        bindService(Intent(this, MessengerService::class.java), connection, BIND_AUTO_CREATE)
    }

    override fun onResume() {
        super.onResume()
        updatePermissions()
        updateSubscriptions()
    }

    override fun onDestroy() {
        if (bound) { service?.removeListener(this); unbindService(connection) }
        super.onDestroy()
    }

    private fun buildUi(): ScrollView {
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.rgb(16, 17, 20)); isFillViewport = true }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(18), dp(16), dp(32))
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(dp(16) + bars.left, dp(18) + bars.top, dp(16) + bars.right, dp(32) + bars.bottom)
            insets
        }
        root.addView(TextView(this).apply {
            text = "Flipper Messenger"
            textSize = 30f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.rgb(255, 130, 0))
        })
        root.addView(TextView(this).apply {
            text = "Compose, read new SMS, and reply from Flipper through your phone."
            textSize = 15f
            setTextColor(Color.LTGRAY)
            setPadding(0, dp(2), 0, dp(14))
        })
        root.addView(card("Connection") {
            status = body("Starting…").also(::addView)
            devicesButton = button("Scan for my Flipper") { service?.startScan() }.also(::addView)
            addView(secondaryButton("Disconnect") { service?.disconnect() })
        })
        root.addView(card("Authorize once") {
            addView(body("1  Flipper: Settings → Authorize phone (60s)\n2  Phone: tap Authorize this phone\n3  Confirm the Bluetooth PIN on both devices"))
            addView(button("Authorize this phone") { service?.provision() })
            addView(secondaryButton("Forget authorization") {
                AlertDialog.Builder(this@MainActivity).setTitle("Forget phone?")
                    .setMessage("This removes Android's link key. Also choose Forget phone on the Flipper.")
                    .setNegativeButton("Cancel", null).setPositiveButton("Forget") { _, _ -> service?.forget() }.show()
            })
        })
        root.addView(card("Permissions") {
            permissionState = body("").also(::addView)
            addView(button("Grant required permissions") { PermissionManager.request(this@MainActivity) })
            addView(secondaryButton("Open Android app settings") {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            })
        })
        root.addView(card("SMS & contacts") {
            addView(body("Choose the sending SIM. Only contacts you check are copied to Flipper; inbox and call logs are never read."))
            simSpinner = Spinner(this@MainActivity).also(::addView)
            addView(button("Select and sync contacts") {
                if (!PermissionManager.contactsGranted(this@MainActivity)) PermissionManager.request(this@MainActivity)
                else contactPicker.launch(Intent(this@MainActivity, ContactPickerActivity::class.java))
            })
        })
        root.addView(card("About & privacy") {
            addView(body("Version ${BuildConfig.VERSION_NAME}\n\nSend to any valid number entered on Flipper while this phone is connected and authorized. No cloud, ads, analytics, stored-inbox access, call logs, or phone-call permission. Link keys are protected by Android Keystore and request IDs prevent accidental duplicate sends.\n\nGNU GPL v3 or later."))
            addView(secondaryButton("View privacy policy") {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://kernferm.github.io/Flipper-Messenger/privacy/")))
            })
        })
        scroll.addView(
            root,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        return scroll
    }

    private fun card(title: String, content: LinearLayout.() -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(Color.rgb(34, 36, 42)) }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(12)
        }
        addView(TextView(this@MainActivity).apply {
            text = title; textSize = 19f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE)
            setPadding(0, 0, 0, dp(8))
        })
        content()
    }
    private fun body(text: String) = TextView(this).apply {
        this.text = text; textSize = 14f; setTextColor(Color.rgb(220, 220, 224)); setPadding(0, 0, 0, dp(8))
    }
    private fun button(text: String, action: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        setTypeface(typeface, Typeface.BOLD)
        backgroundTintList = ColorStateList.valueOf(Color.rgb(255, 130, 0))
        setTextColor(Color.BLACK)
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }
    private fun secondaryButton(text: String, action: () -> Unit) = button(text, action).apply {
        backgroundTintList = ColorStateList.valueOf(Color.rgb(67, 70, 78)); setTextColor(Color.WHITE)
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun updatePermissions() {
        fun state(permission: String) = if (checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) "Granted" else "Required"
        val bluetooth = if (Build.VERSION.SDK_INT >= 31)
            "Scan ${state(Manifest.permission.BLUETOOTH_SCAN)}, Connect ${state(Manifest.permission.BLUETOOTH_CONNECT)}"
        else "Location for BLE scan: ${state(Manifest.permission.ACCESS_FINE_LOCATION)}"
        permissionState.text = "Bluetooth: $bluetooth\nContacts: ${state(Manifest.permission.READ_CONTACTS)}\nSend SMS: ${state(Manifest.permission.SEND_SMS)}\nReceive new SMS: ${state(Manifest.permission.RECEIVE_SMS)}\nRead stored SMS: Not Required\nPhone/calls: Not Required"
    }

    private fun updateSubscriptions() {
        if (!PermissionManager.smsGranted(this)) {
            subscriptions = emptyList()
            simSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("Grant SMS permission first"))
            return
        }
        subscriptions = try { getSystemService(SubscriptionManager::class.java).activeSubscriptionInfoList ?: emptyList() }
        catch (_: SecurityException) { emptyList() }
        val labels = mutableListOf("Android default SIM")
        labels += subscriptions.map { "${it.displayName} (${it.carrierName})" }
        simSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        val saved = SecureStore(this).selectedSubscription()
        simSpinner.setSelection((subscriptions.indexOfFirst { it.subscriptionId == saved } + 1).coerceAtLeast(0))
        simSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                SecureStore(this@MainActivity).saveSubscription(if (position == 0) -1 else subscriptions[position - 1].subscriptionId)
            }
        }
    }

    override fun onState(text: String) = runOnUiThread { status.text = text }
    override fun onDevices(devices: List<BluetoothDevice>) = runOnUiThread {
        this.devices = devices
        devicesButton.text = if (devices.isEmpty()) "Scan for Flipper" else "Choose Flipper (${devices.size} found)"
        devicesButton.setOnClickListener {
            if (this.devices.isEmpty()) service?.startScan()
            else AlertDialog.Builder(this).setTitle("Choose your Flipper")
                .setItems(this.devices.map { "${it.name ?: "Flipper"}\n${it.address}" }.toTypedArray()) { _, index -> service?.connect(this.devices[index]) }
                .setNegativeButton("Rescan") { _, _ -> service?.startScan() }.show()
        }
    }
    override fun onEvent(text: String) = runOnUiThread { Toast.makeText(this, text, Toast.LENGTH_LONG).show() }
}

