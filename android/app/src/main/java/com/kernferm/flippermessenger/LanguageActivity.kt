package com.kernferm.flippermessenger

import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class LanguageActivity : AppCompatActivity() {
    private data class Language(val tag: String, val label: String)

    private val languages = listOf(
        Language("en", "English"), Language("es", "Español"),
        Language("fr", "Français"), Language("de", "Deutsch"),
        Language("it", "Italiano"), Language("pt", "Português"),
        Language("nl", "Nederlands"), Language("pl", "Polski"),
        Language("ru", "Русский"), Language("uk", "Українська"),
        Language("tr", "Türkçe"), Language("ar", "العربية"),
        Language("hi", "हिन्दी"), Language("bn", "বাংলা"),
        Language("ur", "اردو"), Language("id", "Bahasa Indonesia"),
        Language("vi", "Tiếng Việt"), Language("th", "ไทย"),
        Language("ja", "日本語"), Language("ko", "한국어"),
        Language("zh-Hans", "简体中文"), Language("zh-Hant", "繁體中文"),
        Language("sv", "Svenska"), Language("nb", "Norsk bokmål"),
        Language("da", "Dansk"),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.language_title)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setBackgroundColor(Color.rgb(16, 17, 20))
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(dp(16) + bars.left, dp(16) + bars.top, dp(16) + bars.right, dp(16) + bars.bottom)
            insets
        }
        root.addView(TextView(this).apply {
            text = getString(R.string.language_info)
            textSize = 17f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, dp(12))
        })
        val current = AppCompatDelegate.getApplicationLocales().toLanguageTags()
        val list = ListView(this).apply {
            choiceMode = ListView.CHOICE_MODE_SINGLE
            adapter = ArrayAdapter(
                this@LanguageActivity,
                android.R.layout.simple_list_item_single_choice,
                languages.map { it.label },
            )
            val selected = languages.indexOfFirst { current.startsWith(it.tag) }
            if (selected >= 0) setItemChecked(selected, true)
            setOnItemClickListener { _, _, position, _ ->
                AppCompatDelegate.setApplicationLocales(
                    LocaleListCompat.forLanguageTags(languages[position].tag),
                )
                Toast.makeText(this@LanguageActivity, R.string.language_applied, Toast.LENGTH_SHORT).show()
            }
        }
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
