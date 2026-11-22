package io.eksoda.core.data.imports

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.Executors

/** The notification of a finished import, while the app is in the background. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class ImportNotifierTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val context: Application = ApplicationProvider.getApplicationContext()
    private val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private lateinit var preferences: DataStore<Preferences>
    private lateinit var notifier: ImportNotifier
    private val receipt = Persisted("r1", "created", "METRO", 2110)

    @Before
    fun setUp() {
        // The notifier asks the main thread whether the app is visible.
        Dispatchers.setMain(UnconfinedTestDispatcher())
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        preferences = PreferenceDataStoreFactory.create(scope = scope) {
            folder.root.resolve("settings.preferences_pb")
        }
        notifier = ImportNotifier(context, preferences)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        scope.cancel()
        dispatcher.close()
    }

    private fun posted(): List<Notification> =
        shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications

    private suspend fun turnOn() {
        preferences.edit { it[ImportNotifier.NOTIFY_IMPORTS_KEY] = true }
    }

    @Test
    fun `nothing is shown until import notifications are turned on`() = runTest {
        notifier.imported(receipt)
        assertThat(posted()).isEmpty()
    }

    @Test
    fun `the notification names the merchant and the total, never the link`() = runTest {
        turnOn()
        notifier.imported(receipt)
        val notification = posted().single()
        val title = notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString()
        val text = notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        assertThat(title).contains("METRO")
        assertThat(text).contains("21")
        assertThat(title + text).doesNotContain("http")
        assertThat(notification.channelId).isEqualTo("imports")
        assertThat(notification.flags and Notification.FLAG_AUTO_CANCEL != 0).isTrue()
    }

    @Test
    fun `without the notification permission nothing is shown`() = runTest {
        turnOn()
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        notifier.imported(receipt)
        assertThat(posted()).isEmpty()
    }

    @Test
    fun `an expedited import runs with a quiet notification of its own`() {
        val info = notifier.progress()
        assertThat(info.notificationId).isEqualTo(1)
        val notification = info.notification
        assertThat(notification.channelId).isEqualTo("import_progress")
        assertThat(notification.flags and Notification.FLAG_ONGOING_EVENT != 0).isTrue()
        val channel = context.getSystemService(NotificationManager::class.java)
            .getNotificationChannel("import_progress")
        assertThat(channel).isNotNull()
    }
}
