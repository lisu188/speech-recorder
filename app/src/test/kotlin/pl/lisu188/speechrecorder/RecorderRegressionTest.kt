package pl.lisu188.speechrecorder

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RecorderRegressionTest {
    private lateinit var context: Context

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        context.getSharedPreferences("recorder", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("storage_settings", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun allScreensOpenWithoutMicrophoneOrCloudFolder() {
        Robolectric.buildActivity(MainActivity::class.java).create().start().resume().pause().stop().destroy()
        Robolectric.buildActivity(SettingsActivity::class.java).create().start().resume().pause().stop().destroy()
        Robolectric.buildActivity(RecordingsActivity::class.java).create().start().resume().pause().stop().destroy()
    }

    @Test fun appDoesNotRequestInternetOrNotificationPermission() {
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        val requested = info.requestedPermissions.orEmpty().toSet()
        assertFalse("INTERNET must stay removed", Manifest.permission.INTERNET in requested)
        assertFalse("POST_NOTIFICATIONS must stay removed", Manifest.permission.POST_NOTIFICATIONS in requested)
        assertTrue(Manifest.permission.RECORD_AUDIO in requested)
        assertTrue(Manifest.permission.FOREGROUND_SERVICE in requested)
    }

    @Test fun rebootReceiverRequiresManualRestartWithoutPostingNotification() {
        context.getSharedPreferences("recorder", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("enabled", true)
            .putBoolean("speech_active", true)
            .commit()

        BootReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))

        val prefs = context.getSharedPreferences("recorder", Context.MODE_PRIVATE)
        assertFalse(prefs.getBoolean("enabled", true))
        assertFalse(prefs.getBoolean("speech_active", true))
        assertTrue(prefs.getString("capture_error", "").orEmpty().contains("ręcznego uruchomienia"))
    }
}
