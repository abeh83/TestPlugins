package com.indo

import android.content.Context
import android.app.AlertDialog
import android.widget.Toast
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@CloudstreamPlugin
class IdlixPlugin : Plugin() {

    private val loginScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun load(context: Context) {
        val provider = Idlix()
        
        // 1. Initialize SharedPreferences and load the session
        provider.prefs = context.getSharedPreferences("idlix_auth", Context.MODE_PRIVATE)
        provider.loadSession()

        // 2. Register the API
        registerMainAPI(provider)

        // 3. Handle Settings / Login
        openSettings = { ctx ->
            if (provider.hasAccountSession()) {
                AlertDialog.Builder(ctx)
                    .setTitle("Idlix Account")
                    .setMessage("Account session available")
                    .setNegativeButton("Close", null)
                    .setPositiveButton("Logout") { _, _ ->
                        provider.logout()
                        Toast.makeText(ctx, "Logged out", Toast.LENGTH_SHORT).show()
                    }
                    .show()
            } else {
                AlertDialog.Builder(ctx)
                    .setTitle("Idlix Login")
                    .setMessage("You will be redirected to the Idlix website to log in. After a successful login, the window will close automatically.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Login") { _, _ ->
                        Toast.makeText(ctx, "Opening login page...", Toast.LENGTH_SHORT).show()
                        
                        loginScope.launch {
                            val success = provider.loginWithWebView()
                            if (success) {
                                Toast.makeText(ctx, "Login successful!", Toast.LENGTH_LONG).show()
                            } else {
                                Toast.makeText(ctx, "Login failed or was cancelled.", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                    .show()
            }
        }
    }
}
