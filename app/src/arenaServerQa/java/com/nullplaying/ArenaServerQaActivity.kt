package com.nullplaying

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.nullplaying.remote.ARENA_SERVER_QA_APPLICATION_ID
import com.nullplaying.remote.ArenaServerQaRuntimeConfig

/** ADB-launchable entry point that audits the isolated variant before opening the normal game. */
class ArenaServerQaActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        check(BuildConfig.APPLICATION_ID == ARENA_SERVER_QA_APPLICATION_ID)
        check(BuildConfig.DEBUG)
        check(!BuildConfig.REMOTE_SERVICES_ENABLED)
        check(BuildConfig.SHARED_PLAYER_QA_TRANSPORT_ENABLED)
        check(BuildConfig.SHARED_PLAYER_SYNC_ENABLED)
        check(BuildConfig.ADVENTURE_SYSTEM_ENABLED)
        check(BuildConfig.ARENA_SERVER_MATCHING_ENABLED)
        check(BuildConfig.SUPABASE_URL.isBlank())
        check(BuildConfig.SUPABASE_PUBLISHABLE_KEY.isBlank())

        val destinationReady = ArenaServerQaRuntimeConfig.fromBuildConfig().enabled
        Toast.makeText(
            this,
            if (destinationReady) {
                "Arena Server QA isolation verified"
            } else {
                "Arena Server QA destination is not configured"
            },
            Toast.LENGTH_LONG,
        ).show()
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
