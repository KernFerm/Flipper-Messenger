package com.kernferm.flippermessenger

import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class FlipperInstallActivity : AppCompatActivity() {
    companion object {
        const val FAP_URL = "https://github.com/KernFerm/Flipper-Messenger/releases/download/Flipper-Messenger-0045_Flipper-0010/flipper-messenger-flipper-0.0.10.fap"
        const val FAP_NAME = "flipper_messenger.fap"
    }

    private val chooseFap = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newUri(contentResolver, FAP_NAME, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(share, getString(R.string.share_fap_title)))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.flipper_install_title)
        setContentView(buildUi())
    }

    private fun buildUi(): ScrollView {
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.rgb(16, 17, 20)) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(18), dp(16), dp(32))
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(dp(16) + bars.left, dp(18) + bars.top, dp(16) + bars.right, dp(32) + bars.bottom)
            insets
        }
        root.addView(heading(getString(R.string.flipper_install_title)))
        root.addView(body(getString(R.string.install_intro)))
        root.addView(button(getString(R.string.download_fap)) { downloadFap() })
        root.addView(heading(getString(R.string.transfer_heading), 18f))
        root.addView(body(getString(R.string.transfer_steps)))
        root.addView(button(getString(R.string.share_fap)) {
            Toast.makeText(this, R.string.choose_fap_first, Toast.LENGTH_LONG).show()
            chooseFap.launch(arrayOf("application/octet-stream", "application/*", "*/*"))
        })
        root.addView(button(getString(R.string.open_release_page)) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(FAP_URL)))
        })

        val cmd = "curl.exe -L \"$FAP_URL\" -o \"%USERPROFILE%\\Downloads\\$FAP_NAME\" && py \"%USERPROFILE%\\.ufbt\\current\\scripts\\runfap.py\" -p auto -s \"%USERPROFILE%\\Downloads\\$FAP_NAME\" -t \"/ext/apps/Tools/$FAP_NAME\""
        val ps = "Invoke-WebRequest -Uri '$FAP_URL' -OutFile \"${'$'}env:USERPROFILE\\Downloads\\$FAP_NAME\"; python \"${'$'}env:USERPROFILE\\.ufbt\\current\\scripts\\runfap.py\" -p auto -s \"${'$'}env:USERPROFILE\\Downloads\\$FAP_NAME\" -t \"/ext/apps/Tools/$FAP_NAME\""
        addCommand(root, getString(R.string.cmd_heading), cmd)
        addCommand(root, getString(R.string.powershell_heading), ps)
        root.addView(body(getString(R.string.install_finish)))
        scroll.addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return scroll
    }

    private fun addCommand(root: LinearLayout, title: String, command: String) {
        root.addView(heading(title, 18f))
        root.addView(body(command).apply { setTextIsSelectable(true) })
        root.addView(button(getString(R.string.copy_command)) {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(title, command))
            Toast.makeText(this, R.string.command_copied, Toast.LENGTH_SHORT).show()
        })
    }

    private fun downloadFap() {
        try {
            val request = DownloadManager.Request(Uri.parse(FAP_URL))
                .setTitle("Flipper Messenger 0.0.10")
                .setDescription(FAP_NAME)
                .setMimeType("application/octet-stream")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, FAP_NAME)
            getSystemService(DownloadManager::class.java).enqueue(request)
            Toast.makeText(this, R.string.download_started, Toast.LENGTH_LONG).show()
        } catch (_: Exception) {
            Toast.makeText(this, R.string.download_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun heading(text: String, size: Float = 26f) = TextView(this).apply {
        this.text = text; textSize = size; setTypeface(typeface, Typeface.BOLD)
        setTextColor(Color.WHITE); setPadding(0, dp(8), 0, dp(10))
    }
    private fun body(text: String) = TextView(this).apply {
        this.text = text; textSize = 15f; setTextColor(Color.LTGRAY); setPadding(0, 0, 0, dp(12))
    }
    private fun button(text: String, action: () -> Unit) = Button(this).apply {
        this.text = text; isAllCaps = false; setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(8)
        }
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
