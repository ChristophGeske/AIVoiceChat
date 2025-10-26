package com.example.advancedvoice

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.navigateUp
import com.google.android.material.appbar.MaterialToolbar

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val toolbar: MaterialToolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                val navHost = supportFragmentManager
                    .findFragmentById(R.id.nav_host_fragment_content_main) as NavHostFragment
                val navController = navHost.navController

                val current = navController.currentDestination?.id
                when (current) {
                    R.id.settingsFragment -> {
                        // Already on settings -> go back to conversation
                        navController.navigateUp()
                    }
                    else -> {
                        // Navigate from conversation to settings
                        navController.navigate(R.id.action_conversation_to_settings)
                    }
                }
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}