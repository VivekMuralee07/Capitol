package com.capitol.app.ui.lock

import android.os.SystemClock
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.capitol.app.ui.theme.CapitolColors

/**
 * Fingerprint / face / device-PIN lock. The app locks on a cold start and after
 * [RELOCK_AFTER_MS] in the background while the lock is enabled.
 */
object AppLock {
    const val RELOCK_AFTER_MS = 60_000L
    private const val AUTHENTICATORS = BIOMETRIC_WEAK or DEVICE_CREDENTIAL

    var locked by mutableStateOf(false)
        private set
    private var backgroundedAt: Long? = null

    fun canLock(activity: FragmentActivity) =
        BiometricManager.from(activity).canAuthenticate(AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS

    /** Call from onStart. [coldStart] is true for the first start of the process. */
    fun onForeground(enabled: Boolean, coldStart: Boolean) {
        val away = backgroundedAt?.let { SystemClock.elapsedRealtime() - it }
        if (enabled && (coldStart || (away != null && shouldRelock(away)))) locked = true
        if (!enabled) locked = false
        backgroundedAt = null
    }

    fun onBackground() { backgroundedAt = SystemClock.elapsedRealtime() }

    internal fun shouldRelock(awayMs: Long) = awayMs >= RELOCK_AFTER_MS

    fun authenticate(activity: FragmentActivity, title: String, onSuccess: () -> Unit, onFailure: (String) -> Unit = {}) {
        val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { locked = false; onSuccess() }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) { onFailure(errString.toString()) }
        })
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setSubtitle("Use your fingerprint, face or screen lock")
                .setAllowedAuthenticators(AUTHENTICATORS)
                .build()
        )
    }
}

@Composable
fun LockScreen(activity: FragmentActivity) {
    var error by androidx.compose.runtime.remember { mutableStateOf<String?>(null) }
    val unlock = { AppLock.authenticate(activity, "Unlock Capitol", onSuccess = {}, onFailure = { error = it }) }
    LaunchedEffect(Unit) { unlock() }
    val mc = CapitolColors.market
    Box(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(mc.heroStart, MaterialTheme.colorScheme.background))),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center, modifier = Modifier.padding(32.dp)) {
            Box(Modifier.size(72.dp).clip(RoundedCornerShape(22.dp)).background(Brush.linearGradient(listOf(mc.chart1, mc.bull))),
                contentAlignment = Alignment.Center) { Icon(Icons.Default.Lock, null, tint = Color.White, modifier = Modifier.size(36.dp)) }
            Spacer(Modifier.height(20.dp))
            Text("Capitol is locked", style = MaterialTheme.typography.headlineSmall)
            Text("Unlock to view your portfolio and connections", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            error?.let { Spacer(Modifier.height(8.dp)); Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Spacer(Modifier.height(24.dp))
            Button(onClick = unlock) { Text("Unlock") }
        }
    }
}
