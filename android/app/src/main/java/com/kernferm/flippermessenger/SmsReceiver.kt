package com.kernferm.flippermessenger

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony

/** Forwards only newly received SMS while the already-running authorized BLE service is active. */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isEmpty()) return
        val sender = messages.firstNotNullOfOrNull { it.originatingAddress } ?: return
        val body = messages.joinToString(separator = "") { it.messageBody.orEmpty() }
        if (body.isEmpty()) return
        val binder = peekService(context, Intent(context, MessengerService::class.java)) as? MessengerService.LocalBinder
        binder?.service()?.forwardIncoming(sender, body)
    }
}
