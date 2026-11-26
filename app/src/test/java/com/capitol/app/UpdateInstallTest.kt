package com.capitol.app

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.capitol.app.update.UpdateManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Regression test: the downloaded update must be shareable through the FileProvider. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UpdateInstallTest {
    @Test fun downloadedApkCanBeHandedToInstaller() {
        val ctx = ApplicationProvider.getApplicationContext<CapitolApp>()
        val apk = UpdateManager.apkFile(ctx).apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1, 2, 3)) }
        // Throws IllegalArgumentException if file_paths.xml doesn't cover the APK's folder.
        val intent = UpdateManager.installIntent(ctx, apk)
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("application/vnd.android.package-archive", intent.type)
        assertEquals("content", intent.data!!.scheme)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }
}
