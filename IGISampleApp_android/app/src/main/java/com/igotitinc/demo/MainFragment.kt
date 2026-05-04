package com.igotitinc.demo

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.igotitinc.demo.databinding.FragmentMainBinding
import com.igotitinc.sdk.IGIManager
import com.igotitinc.sdk.IGIManagerCallback
import com.igotitinc.sdk.privacy.IGIPrivacyStatus
import com.igotitinc.sdk.ui.mainTabs.IGIMainActivity
import java.util.Date

class MainFragment : Fragment() {

    private var _binding: FragmentMainBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMainBinding.inflate(inflater, container, false)
        val view = binding.root

        // Privacy status changed shape between 3.x and 4.0.0:
        //   - Legacy: `IGIManager.getInstance().privacyStatus` was a
        //     property; values were `IGI_PRIVACY_STATUS.IGI_PRIVACY_STATUS_OPT_IN`
        //     etc. (Java-style enum-ish constants).
        //   - 4.0.0: `getPrivacyStatus()` / `setPrivacyStatus(_)` methods
        //     on `IGIManager`, taking the typed Kotlin enum
        //     `IGIPrivacyStatus { OptOut, OptIn }` (matches iOS naming).
        //
        // The unused-variable read here just demonstrates the getter for
        // the sample; production code would gate UI on it.
        @Suppress("UNUSED_VARIABLE")
        val privacyStatus = IGIManager.getInstance().getPrivacyStatus()
        IGIManager.getInstance().setPrivacyStatus(IGIPrivacyStatus.OptIn)

        binding.button.setOnClickListener {
            // `startUserSession` keeps the same parameter order as 3.x;
            // only the IGIManagerCallback construction shape changes
            // (SAM lambda + `Throwable?` error type — see
            // IGISampleApplication.kt for the same migration).
            IGIManager.getInstance().startUserSession(
                "ghi",
                "jkl",
                "ghi@jkl.com",
                Date(319161600000),
                "9999999",
                IGIManagerCallback { _, error ->
                    if (error == null) {
                        showEvents()
                    } else {
                        Log.e("IGI", error.localizedMessage ?: "unknown")
                    }
                }
            )
        }

        return view
    }

    private fun showEvents() {
        val i = Intent(activity, IGIMainActivity::class.java)
        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        startActivity(i)
    }
}
