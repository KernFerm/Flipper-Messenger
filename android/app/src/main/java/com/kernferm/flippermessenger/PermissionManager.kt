package com.kernferm.flippermessenger

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

object PermissionManager {
    const val REQUEST = 700
    fun required(): Array<String> = buildList {
        if (Build.VERSION.SDK_INT >= 31) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        } else add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.SEND_SMS)
        add(Manifest.permission.RECEIVE_SMS)
        add(Manifest.permission.READ_CONTACTS)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    fun granted(activity: Activity, permission: String) =
        ContextCompat.checkSelfPermission(activity, permission) == PackageManager.PERMISSION_GRANTED

    fun request(activity: Activity) = ActivityCompat.requestPermissions(activity, required(), REQUEST)
    fun smsGranted(activity: Activity) = granted(activity, Manifest.permission.SEND_SMS)
    fun contactsGranted(activity: Activity) = granted(activity, Manifest.permission.READ_CONTACTS)
}

