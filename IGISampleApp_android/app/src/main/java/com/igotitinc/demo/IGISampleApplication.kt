package com.igotitinc.demo

import android.app.Application
import android.util.Log
// Imports moved from the root `igi_sdk.*` package (legacy 3.x — no domain
// prefix) to the standard `com.igotitinc.sdk.*` package (4.0.0+). The new
// SDK keeps the parameterless `IGIManager.getInstance()` shape and the
// `IGI_SDK_*_MODE` string constants for legacy-call-site compatibility,
// so the only edit per import line is the package path.
import com.igotitinc.sdk.IGIManager
import com.igotitinc.sdk.IGIManagerCallback

class IGISampleApplication: Application() {

    override fun onCreate() {
        super.onCreate()

        val apiKey = "ff64-f61c-7ff8-d183-b746-731b-e3f1"
        val subDomain = "demo"

        // Two changes from the 3.x call shape:
        //   1. `IGIManagerCallback(function = { ... })` (the legacy
        //      named-arg constructor) → bare SAM lambda
        //      `IGIManagerCallback { ... }`. The new
        //      `fun interface IGIManagerCallback` lets Kotlin and Java
        //      both write the lambda directly.
        //   2. The error parameter is now `Throwable?` instead of the
        //      legacy `Error?`. Existing `error.localizedMessage`
        //      access keeps working since `Throwable` carries that too.
        // The `IGIManager.IGI_SDK_SANDBOX_MODE` string constant is
        // preserved on `IGIManager`'s companion in 4.0.0 for partners
        // who copy-pasted from the legacy SDK; the typed
        // `IGIEnvironment.SANDBOX` enum is the recommended replacement
        // for new code.
        IGIManager.getInstance().initialize(
            apiKey,
            IGIManager.IGI_SDK_DEV_MODE,
            subDomain,
            this,
            IGIManagerCallback { _, error ->
                if (error != null) {
                    Log.e("IGI", error.localizedMessage ?: "unknown")
                }
            }
        )

        IGIManager.getInstance().setAnalyticsListener(AnalyticsManager())
    }
}
