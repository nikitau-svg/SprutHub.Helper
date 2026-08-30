package io.github.nikitau.spruthubhelper.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle

class TilePreferenceActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }
}
