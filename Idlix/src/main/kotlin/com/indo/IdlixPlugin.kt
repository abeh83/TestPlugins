package com.indo

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@CloudstreamPlugin
class IdlixPlugin : Plugin() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun load(context: Context) {
        val provider = Idlix()

        // Restore the saved session (if any) before the app uses the provider
        provider.initSession(context)

        registerMainAPI(provider)

        openSettings = { ctx ->
            if (provider.hasAccountSession()) {
                AlertDialog.Builder(ctx)
                    .setTitle("Idlix Account")
                    .setMessage(
						"An account session is available. " +
						"Saved sessions may be restored after restarting the app."
					)
                    .setNegativeButton("Close", null)
                    .setPositiveButton("Logout") { _, _ ->
                        provider.logout()
                        Toast.makeText(ctx, "Logged out", Toast.LENGTH_SHORT).show()
                    }
                    .show()
            } else {
                val layout = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(48, 24, 48, 0)
                }

                val username = EditText(ctx).apply {
                    hint = "Username or email"
                    inputType = InputType.TYPE_CLASS_TEXT
                    setSingleLine(true)
                }

                val password = EditText(ctx).apply {
                    hint = "Password"
                    inputType = InputType.TYPE_CLASS_TEXT or
                        InputType.TYPE_TEXT_VARIATION_PASSWORD
                    setSingleLine(true)
                }

                layout.addView(username)
                layout.addView(password)

                AlertDialog.Builder(ctx)
                    .setTitle("Idlix Login")
                    .setView(layout)
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Login") { _, _ ->
                        val identifier = username.text.toString().trim()
                        val passwordValue = password.text.toString()
                        password.text.clear()

                        if (identifier.isBlank() || passwordValue.isEmpty()) {
                            Toast.makeText(
                                ctx,
                                "Please enter username and password",
                                Toast.LENGTH_SHORT
                            ).show()
                            return@setPositiveButton
                        }

                        scope.launch {
                            Toast.makeText(ctx, "Logging in…", Toast.LENGTH_SHORT).show()

                            // Real network login; tokens are saved inside login()
                            val ok = withContext(Dispatchers.IO) {
                                provider.login(identifier, passwordValue)
                            }

                            Toast.makeText(
                                ctx,
                                if (ok) "Account session established"
                                else "Unable to establish account session",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                    .show()
            }
        }
    }
}
