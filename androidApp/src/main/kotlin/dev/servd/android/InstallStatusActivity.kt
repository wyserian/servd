package dev.servd.android

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Bundle
import android.widget.Toast

/**
 * Where the system installer reports on an update (see [Updater]): ask the user to confirm, or say
 * why it failed. Not exported, so no other app can hand it an intent to launch.
 */
class InstallStatusActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)?.let { runCatching { startActivity(it) } }
            }
            PackageInstaller.STATUS_SUCCESS -> {}
            else -> {
                val why = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "unknown error"
                Toast.makeText(applicationContext, "Update not installed: $why", Toast.LENGTH_LONG).show()
            }
        }
        finish()
    }
}
