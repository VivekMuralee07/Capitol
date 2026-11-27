package com.capitol.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.capitol.app.data.broker.KiteConnectClient
import com.capitol.app.data.prefs.AppSettings
import com.capitol.app.data.prefs.ThemeMode
import com.capitol.app.ui.CapitolNavHost
import com.capitol.app.ui.theme.CapitolTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : androidx.fragment.app.FragmentActivity() {
    private var coldStart = true
    private val container get() = (application as CapitolApp).container

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        handleDeepLink(intent)
        setContent {
            val settings by container.settings.settings.collectAsStateWithLifecycle(AppSettings())
            val dark = when (settings.themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            LaunchedEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
                )
            }
            CapitolTheme(darkTheme = dark, dynamicColor = settings.dynamicColor) {
                CapitolNavHost(container, dark)
                if (com.capitol.app.ui.lock.AppLock.locked) com.capitol.app.ui.lock.LockScreen(this@MainActivity)
            }
        }
        lifecycleScope.launch {
            if (container.settings.settings.first().autoCheckUpdates) container.updates.check(silent = true)
        }
    }

    override fun onStart() {
        super.onStart()
        val enabled = kotlinx.coroutines.runBlocking { container.settings.settings.first().appLock }
        com.capitol.app.ui.lock.AppLock.onForeground(enabled, coldStart)
        coldStart = false
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) com.capitol.app.ui.lock.AppLock.onBackground()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDeepLink(intent)
    }

    /** Handles capitol://kite-callback?request_token=... if Kite redirects via the browser. */
    private fun handleDeepLink(intent: Intent?) {
        val data = intent?.data?.toString() ?: return
        if (com.capitol.app.data.mcp.KiteMcp.isRedirect(data)) {
            lifecycleScope.launch { runCatching { container.kiteMcp.completeLogin(data); container.portfolio.refresh() } }
            return
        }
        if (com.capitol.app.data.mcp.IndMoneyMcp.isRedirect(data)) {
            lifecycleScope.launch { runCatching { container.indmoneyMcp.completeLogin(data) } }
            return
        }
        val token = KiteConnectClient.requestTokenFrom(data) ?: return
        lifecycleScope.launch {
            runCatching { container.portfolio.kite.createSession(token) }
            container.portfolio.refresh()
        }
    }
}
