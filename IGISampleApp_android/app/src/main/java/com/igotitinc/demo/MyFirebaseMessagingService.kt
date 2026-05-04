package com.igotitinc.demo

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.igotitinc.sdk.IGIManager

class MyFirebaseMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        IGIManager.getInstance().setDeviceToken(token)
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        if (remoteMessage.notification != null) {
            // `shouldHandleRemoteMessage` was a static method on the
            // legacy `IGIManager`; in 4.0.0 it's an instance method on
            // the singleton (matches the rest of the post-init API
            // surface). Same return semantics: `true` for IGI-branded
            // payloads the SDK knows how to route.
            if (IGIManager.getInstance().shouldHandleRemoteMessage(remoteMessage.data)) {
                IGIManager.getInstance().handleRemoteMessage(remoteMessage.data)
            } else {
                // Non-IGI push — handle host-side here if your app
                // dispatches its own notifications.
            }
        }
    }
}
