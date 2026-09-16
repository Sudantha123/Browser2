package com.browser2.app

import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.RadioButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    private lateinit var homeInput: EditText
    private lateinit var engineGoogle: RadioButton
    private lateinit var engineDdg: RadioButton
    private lateinit var engineBing: RadioButton
    private lateinit var jsBox: CheckBox
    private lateinit var desktopBox: CheckBox
    private lateinit var cacheBox: CheckBox
    private lateinit var thirdPartyBox: CheckBox
    private lateinit var saveButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        homeInput = findViewById(R.id.home_input)
        engineGoogle = findViewById(R.id.engine_google)
        engineDdg = findViewById(R.id.engine_ddg)
        engineBing = findViewById(R.id.engine_bing)
        jsBox = findViewById(R.id.box_js)
        desktopBox = findViewById(R.id.box_desktop)
        cacheBox = findViewById(R.id.box_cache)
        thirdPartyBox = findViewById(R.id.box_third_party)
        saveButton = findViewById(R.id.btn_save)
        val back = findViewById<ImageButton>(R.id.btn_settings_back)

        loadPrefs()

        back.setOnClickListener { finish() }
        saveButton.setOnClickListener {
            var home = homeInput.text.toString().trim()
            if (home.isNotEmpty() && !home.contains("://")) home = "https://$home"
            Prefs.setHome(this, home.ifBlank { "https://www.google.com" })
            Prefs.setSearchEngine(this, selectedEngine())
            Prefs.setJavascriptEnabled(this, jsBox.isChecked)
            Prefs.setDesktopModeDefault(this, desktopBox.isChecked)
            Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun selectedEngine(): String = when {
        engineDdg.isChecked -> "https://duckduckgo.com/?q=%s"
        engineBing.isChecked -> "https://www.bing.com/search?q=%s"
        else -> "https://www.google.com/search?q=%s"
    }

    private fun loadPrefs() {
        homeInput.setText(Prefs.home(this))
        when {
            Prefs.searchEngine(this).contains("duckduckgo") -> engineDdg.isChecked = true
            Prefs.searchEngine(this).contains("bing") -> engineBing.isChecked = true
            else -> engineGoogle.isChecked = true
        }
        jsBox.isChecked = Prefs.javascriptEnabled(this)
        desktopBox.isChecked = Prefs.desktopModeDefault(this)
        cacheBox.isChecked = true
        thirdPartyBox.isChecked = true
    }
}
