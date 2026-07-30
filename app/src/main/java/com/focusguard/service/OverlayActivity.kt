package com.focusguard.service

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.ComponentActivity

class OverlayActivity : ComponentActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private var completionBroadcastSent = false
    private var returningHome = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val appLabel = intent.getStringExtra(EXTRA_APP_LABEL) ?: "this content"
        val reason = intent.getStringExtra(EXTRA_REASON)
            ?: "FocusGuard could not confirm that this content is useful."

        val container = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(250, 7, 29, 25))
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(64, 64, 64, 64)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        }

        content.addView(TextView(this).apply {
            text = "Content blocked"
            textSize = 32f
            gravity = Gravity.CENTER
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
            setTextColor(Color.rgb(240, 243, 238))
        })
        content.addView(TextView(this).apply {
            text = appLabel
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(99, 205, 189))
            setPadding(0, 24, 0, 24)
        })
        content.addView(TextView(this).apply {
            text = reason
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(240, 243, 238))
            setPadding(0, 0, 0, 28)
        })
        content.addView(TextView(this).apply {
            text = "Closing the blocked app and returning Home in a few seconds."
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(162, 181, 175))
            setPadding(0, 0, 0, 28)
        })
        content.addView(Button(this).apply {
            text = "Go Home now"
            backgroundTintList = ColorStateList.valueOf(Color.rgb(99, 205, 189))
            setTextColor(Color.rgb(7, 29, 25))
            isAllCaps = false
            setOnClickListener { beginExit() }
        })

        container.addView(content)
        setContentView(container)

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    beginExit()
                }
            }
        )
        handler.postDelayed(::beginExit, OVERLAY_DURATION_MS)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        sendCompletionBroadcast()
        super.onDestroy()
    }

    private fun beginExit() {
        if (returningHome) return
        returningHome = true
        finishAndRemoveTask()
    }

    private fun sendCompletionBroadcast() {
        if (completionBroadcastSent) return
        completionBroadcastSent = true
        sendBroadcast(
            Intent(FocusAccessibilityService.ACTION_OVERLAY_FINISHED)
                .setPackage(packageName)
        )
    }

    companion object {
        const val EXTRA_APP_LABEL = "app_label"
        const val EXTRA_REASON = "reason"
        private const val OVERLAY_DURATION_MS = 4_000L
    }
}
