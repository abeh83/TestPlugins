package com.indo

import android.content.Context
import android.app.AlertDialog
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import android.text.InputType
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@CloudstreamPlugin
class IdlixPlugin : Plugin() {

    private val loginScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main
    )

    override fun load(context: Context) {

        val provider = Idlix()

        registerMainAPI(provider)

        openSettings = { ctx ->

            if (provider.hasAccountSession()) {

                AlertDialog.Builder(ctx)
                    .setTitle("Idlix Account")
                    .setMessage("Account session available")
                    .setNegativeButton("Close", null)
                    .setPositiveButton("Logout") { _, _ ->
                        provider.logout()
                        Toast.makeText(
                            ctx,
                            "Local session cleared",
                            Toast.LENGTH_SHORT
                        ).show()
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
					
						} else {
					
							loginScope.launch {
					
								val result = provider.testLogin(
									identifier,
									passwordValue
								)
					
								val message = when (result) {
									Idlix.LoginResult.SUCCESS ->
										"Test login successful"
					
									Idlix.LoginResult.INVALID_CREDENTIALS ->
										"Invalid username or password"
					
									Idlix.LoginResult.NETWORK_ERROR ->
										"Network connection error"
					
									Idlix.LoginResult.SESSION_ERROR ->
										"Unable to establish session"
					
									Idlix.LoginResult.UNTRUSTED_ENDPOINT ->
										"Untrusted authentication endpoint"
					
									Idlix.LoginResult.SERVER_ERROR ->
										"Authentication service unavailable"
								}
					
								Toast.makeText(
									ctx,
									message,
									Toast.LENGTH_SHORT
								).show()
							}
						}
					}
					.show()
			}
		}
	}

