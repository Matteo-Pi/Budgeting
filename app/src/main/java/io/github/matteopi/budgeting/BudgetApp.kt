package io.github.matteopi.budgeting

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import io.github.matteopi.budgeting.work.WorkScheduler
import io.github.matteopi.budgeting.work.planner.PlannerNotifications
import kotlinx.coroutines.launch

class BudgetApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)

        createNotificationChannel()
        PlannerNotifications.createChannels(this)

        container.appScope.launch {
            container.categoryRepository.ensureSeeded()
            container.postingService.postDue(container.clock.today())
        }
        // The alarm is lost when the app is force-stopped; setting it again on every start keeps it.
        container.appScope.launch { container.plannerAlarms.reschedule() }

        WorkScheduler.schedule(this)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            REMINDERS_CHANNEL_ID,
            getString(R.string.notification_channel_reminders_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val REMINDERS_CHANNEL_ID = "reminders"
    }
}
