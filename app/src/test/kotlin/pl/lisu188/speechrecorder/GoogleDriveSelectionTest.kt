package pl.lisu188.speechrecorder

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GoogleDriveSelectionTest {
    private lateinit var context: Context

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("storage_settings", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun newInstallStillDefaultsToOneDriveMode() {
        assertFalse(GoogleDriveBackup.isSelected(context))
        assertEquals("OneDrive", GoogleDriveBackup.selectedProvider(context))
        assertFalse(GoogleDriveBackup.hasAccess(context))
    }

    @Test fun googleModeRequiresAuthorizedAccount() {
        context.getSharedPreferences("storage_settings", Context.MODE_PRIVATE).edit()
            .putString("recording_cloud_provider", "google_drive")
            .commit()
        assertTrue(GoogleDriveBackup.isSelected(context))
        assertFalse(GoogleDriveBackup.isConfigured(context))
        assertFalse(GoogleDriveBackup.hasAccess(context))
    }

    @Test fun accountSwitchDoesNotDeleteOneDriveGrant() {
        val prefs = context.getSharedPreferences("storage_settings", Context.MODE_PRIVATE)
        prefs.edit().putString("recording_cloud_provider", "google_drive")
            .putString("google_drive_account", "someone@example.com").commit()
        assertTrue(GoogleDriveBackup.hasAccess(context))
        GoogleDriveBackup.selectOneDrive(context)
        assertFalse(GoogleDriveBackup.isSelected(context))
        assertEquals("someone@example.com", GoogleDriveBackup.account(context))
    }

    @Test fun disconnectClearsGoogleDestination() {
        val prefs = context.getSharedPreferences("storage_settings", Context.MODE_PRIVATE)
        prefs.edit().putString("recording_cloud_provider", "google_drive")
            .putString("google_drive_account", "someone@example.com")
            .putString("google_drive_folder", "abc")
            .putString("google_drive_live_folder", "def").commit()
        GoogleDriveBackup.disconnect(context)
        assertFalse(GoogleDriveBackup.isSelected(context))
        assertNull(GoogleDriveBackup.account(context))
        assertNull(prefs.getString("google_drive_folder", null))
        assertNull(prefs.getString("google_drive_live_folder", null))
    }
}
