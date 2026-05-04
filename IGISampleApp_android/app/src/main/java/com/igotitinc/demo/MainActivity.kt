package com.igotitinc.demo

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.igotitinc.sdk.IGIManager
import com.igotitinc.sdk.ui.mainTabs.IGIMainActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Cold-start push handling: when the user launched the app
        // from a notification tap, FCM puts the payload on the
        // launching Intent's extras.
        if (intent != null && intent.extras != null) {
            handleIGIPayloadIfPresent(intent.extras!!)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)

        // Background push handling: same shape, different lifecycle —
        // the app was already running (warm) and brought to foreground
        // by the notification tap.
        if (intent.extras != null) {
            handleIGIPayloadIfPresent(intent.extras!!)
        }
    }

    /**
     * Replaces the legacy `IGIManager.getInstance().isIGIPayload(extras)`
     * helper, which took a `Bundle` directly. The 4.0.0 SDK exposes the
     * canonical `shouldHandleRemoteMessage(data: Map<String, String>?)`
     * (parity with iOS `IGIManager.shouldHandleRemodeMessage(_:)`), so
     * we project the Bundle onto a String→String Map first. Bundle
     * keys we can't read as String are skipped — push payloads are
     * always String-keyed strings on the wire.
     */
    private fun handleIGIPayloadIfPresent(extras: Bundle) {
        val data = extras.keySet()
            .mapNotNull { key -> extras.getString(key)?.let { value -> key to value } }
            .toMap()
        if (IGIManager.getInstance().shouldHandleRemoteMessage(data)) {
            val i = Intent(this, IGIMainActivity::class.java)
            i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            i.putExtras(extras)
            startActivity(i)
        }
    }
}
