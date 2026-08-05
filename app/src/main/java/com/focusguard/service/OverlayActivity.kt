package com.focusguard.service

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focusguard.ui.theme.DeepForest
import com.focusguard.ui.theme.FocusGuardTheme
import com.focusguard.ui.theme.Mint
import com.focusguard.ui.theme.TextPrimary
import com.focusguard.ui.theme.TextSecondary

/**
 * Blocking screen. Lets the user either go home immediately or pick a timed grace
 * countdown (5/10/15/30 min): the overlay dismisses, the app keeps working, and
 * FocusGuard resumes blocking automatically when the countdown ends.
 */
class OverlayActivity : ComponentActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private var completionBroadcastSent = false
    private var returningHome = false
    private var graceChosen = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val appLabel = intent.getStringExtra(EXTRA_APP_LABEL) ?: "this content"
        val reason = intent.getStringExtra(EXTRA_REASON)
            ?: "FocusGuard could not confirm that this content is useful."

        setContent {
            FocusGuardTheme {
                BlockScreen(appLabel = appLabel, reason = reason, onGoHome = ::goHome, onGrace = ::chooseGrace)
            }
        }

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    goHome()
                }
            }
        )
        handler.postDelayed(::goHome, OVERLAY_DURATION_MS)
    }

    private fun chooseGrace(minutes: Int) {
        if (graceChosen || returningHome) return
        graceChosen = true
        handler.removeCallbacksAndMessages(null)
        sendBroadcast(
            Intent(FocusAccessibilityService.ACTION_GRACE_PERIOD)
                .setPackage(packageName)
                .putExtra(FocusAccessibilityService.EXTRA_GRACE_MINUTES, minutes)
        )
        finishAndRemoveTask()
    }

    private fun goHome() {
        if (returningHome) return
        returningHome = true
        handler.removeCallbacksAndMessages(null)
        finishAndRemoveTask()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (!graceChosen) sendCompletionBroadcast()
        super.onDestroy()
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
        private const val OVERLAY_DURATION_MS = 10_000L
    }
}

private val GRACE_CHOICES = listOf(5, 10, 15, 30)

@Composable
private fun BlockScreen(
    appLabel: String,
    reason: String,
    onGoHome: () -> Unit,
    onGrace: (Int) -> Unit
) {
    Surface(modifier = Modifier.fillMaxSize(), color = DeepForest) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 32.dp, vertical = 48.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Filled.Lock,
                contentDescription = null,
                tint = Mint,
                modifier = Modifier.size(52.dp)
            )
            Spacer(Modifier.height(18.dp))
            Text(
                "Content blocked",
                color = TextPrimary,
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            Text(
                appLabel,
                color = Mint,
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            Text(
                reason,
                color = TextSecondary,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(34.dp))
            Text(
                "Give me a few more minutes — blocking resumes automatically",
                color = TextSecondary,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                GRACE_CHOICES.take(2).forEach { minutes ->
                    GraceChip(minutes, Modifier.weight(1f)) { onGrace(minutes) }
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                GRACE_CHOICES.drop(2).forEach { minutes ->
                    GraceChip(minutes, Modifier.weight(1f)) { onGrace(minutes) }
                }
            }
            Spacer(Modifier.height(30.dp))
            Button(
                onClick = onGoHome,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Mint,
                    contentColor = DeepForest
                ),
                shape = RoundedCornerShape(9.dp),
                modifier = Modifier.fillMaxWidth().height(50.dp)
            ) {
                Text("Go home now", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            }
        }
    }
}

@Composable
private fun GraceChip(minutes: Int, modifier: Modifier = Modifier, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Mint),
        border = BorderStroke(1.dp, Mint),
        contentPadding = PaddingValues(vertical = 10.dp),
        modifier = modifier
    ) {
        Text("$minutes min", fontWeight = FontWeight.SemiBold)
    }
}
