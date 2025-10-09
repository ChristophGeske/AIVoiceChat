package com.example.advancedvoice

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.fragment.NavHostFragment
import com.google.android.material.appbar.MaterialToolbar

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val toolbar: MaterialToolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // Activity owns the single gear menu (Fragment does NOT inflate any menu)
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                // Find FirstFragment and toggle its settings panel (open/close)
                val navHost = supportFragmentManager
                    .findFragmentById(R.id.nav_host_fragment_content_main) as? NavHostFragment
                val frag = navHost?.childFragmentManager?.primaryNavigationFragment
                    ?: navHost?.childFragmentManager?.fragments?.firstOrNull()
                (frag as? FirstFragment)?.toggleSettingsVisibility()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}