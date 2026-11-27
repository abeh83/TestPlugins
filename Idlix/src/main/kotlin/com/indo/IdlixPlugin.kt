package com.indo

import android.content.Context
import android.app.AlertDialog
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class IdlixPlugin : Plugin() {

    override fun load(context: Context) {

        val provider = Idlix()

        registerMainAPI(provider)

        openSettings = { ctx ->
            AlertDialog.Builder(ctx)
                .setTitle("Idlix Account")
                .setMessage(
                    if (provider.hasAccountSession())
                        "Account session available"
                    else
                        "Not logged in"
                )
                .setPositiveButton("Close", null)
                .show()
        }
    }
}
