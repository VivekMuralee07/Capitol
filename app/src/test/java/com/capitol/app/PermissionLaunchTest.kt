package com.capitol.app

import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.FragmentActivity
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression test for the crash in v1.0.8: on FragmentActivity (needed for the biometric app
 * lock), launching a permission request from an ActivityResult launcher threw
 * "Can only use lower 16 bits for requestCode" with the old fragment library biometric pulls in.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class PermissionLaunchTest {
    @Test fun permissionLauncherWorksOnFragmentActivity() {
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        repeat(5) { i -> // request codes are random; try several
            val launcher = activity.activityResultRegistry.register("perm$i", ActivityResultContracts.RequestPermission()) {}
            launcher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            launcher.unregister()
        }
    }
}
