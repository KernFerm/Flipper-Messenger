package com.kernferm.flippermessenger

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.ContactsContract
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class ContactPickerActivity : AppCompatActivity() {
    private data class Contact(val name: String, val phone: String)
    private val contacts = mutableListOf<Contact>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            setResult(Activity.RESULT_CANCELED); finish(); return
        }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(16, 16, 16, 16) }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(16 + bars.left, 16 + bars.top, 16 + bars.right, 16 + bars.bottom)
            insets
        }
        val list = ListView(this).apply { choiceMode = ListView.CHOICE_MODE_MULTIPLE }
        contacts += readContacts()
        list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_multiple_choice,
            contacts.map { "${it.name}\n${it.phone}" })
        val syncSelection = {
            val names = arrayListOf<String>(); val phones = arrayListOf<String>()
            for (i in contacts.indices) if (list.isItemChecked(i) && names.size < 50) {
                names += contacts[i].name; phones += contacts[i].phone
            }
            setResult(Activity.RESULT_OK, Intent().putStringArrayListExtra("names", names).putStringArrayListExtra("phones", phones))
            finish()
        }
        root.addView(TextView(this).apply { text = "Select up to 50 contacts to copy to Flipper"; textSize = 18f })
        root.addView(Button(this).apply {
            text = "Sync selected contacts"
            setOnClickListener { syncSelection() }
        })
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(Button(this).apply {
            text = "Sync selected contacts"
            setOnClickListener { syncSelection() }
        })
        setContentView(root)
    }

    private fun readContacts(): List<Contact> {
        val found = linkedMapOf<String, Contact>()
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER,
            ContactsContract.CommonDataKinds.Phone.NUMBER)
        contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, projection, null, null,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " COLLATE NOCASE")?.use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow(projection[0])
            val normalizedIndex = cursor.getColumnIndexOrThrow(projection[1])
            val numberIndex = cursor.getColumnIndexOrThrow(projection[2])
            while (cursor.moveToNext() && found.size < 500) {
                val phone = cursor.getString(normalizedIndex) ?: cursor.getString(numberIndex) ?: continue
                val clean = phone.filter { it.isDigit() || it == '+' }.take(23)
                if (clean.count(Char::isDigit) !in 3..15) continue
                val name = (cursor.getString(nameIndex) ?: clean).take(31)
                found.putIfAbsent("$name\u0000$clean", Contact(name, clean))
            }
        }
        return found.values.toList()
    }
}

