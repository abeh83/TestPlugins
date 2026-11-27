package com.indo

import android.content.Context
import android.app.AlertDialog
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import android.text.InputType
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class IdlixPlugin : Plugin() {

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
                        password.text.clear()

                        Toast.makeText(
                            ctx,
                            "Login UI test",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    .show()
            }
        }
    }
}
