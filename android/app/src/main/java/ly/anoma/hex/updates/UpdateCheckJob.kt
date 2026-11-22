package ly.anoma.hex.updates

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import ly.anoma.hex.MainActivity
import ly.anoma.hex.R

class UpdateCheckJob : JobService() {
    private var check: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        if (!enabled(this)) return false
        check = CoroutineScope(Dispatchers.Main.immediate).launch {
            val retry = try {
                val release = AppUpdates.latest(this@UpdateCheckJob)
                if (release != null && enabled(this@UpdateCheckJob)) notify(release)
                false
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                true
            }
            jobFinished(params, retry)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        check?.cancel()
        return enabled(this)
    }

    private fun notify(release: VoiceKbRelease) {
        val version = release.version.toString()
        val prefs = AppUpdates.preferences(this)
        if (prefs.getString(LAST_NOTIFIED, null) == version) return
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            return
        val notifications = getSystemService(NotificationManager::class.java)
        if (!notifications.areNotificationsEnabled()) return
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.settings_updates), NotificationManager.IMPORTANCE_DEFAULT))
        val open = Intent(this, MainActivity::class.java).putExtra(OPEN_UPDATES, true)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        notifications.notify(UPDATE_JOB, Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_update_notification)
            .setContentTitle(getString(R.string.update_available, version))
            .setContentText(getString(R.string.update_notification_detail))
            .setContentIntent(PendingIntent.getActivity(this, UPDATE_JOB, open,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .setAutoCancel(true)
            .build())
        prefs.edit().putString(LAST_NOTIFIED, version).apply()
    }

    companion object {
        internal const val OPEN_UPDATES = "ly.anoma.hex.OPEN_UPDATES"
        private const val UPDATE_JOB = 43021
        private const val CHANNEL = "app-updates"
        private const val ENABLED = "background-checks"
        private const val LAST_NOTIFIED = "last-notified"

        internal fun enabled(context: Context) = AppUpdates.preferences(context).getBoolean(ENABLED, false)

        internal fun setEnabled(context: Context, enabled: Boolean) {
            AppUpdates.preferences(context).edit().putBoolean(ENABLED, enabled).apply()
            schedule(context)
            if (!enabled) context.getSystemService(NotificationManager::class.java).cancel(UPDATE_JOB)
        }

        internal fun schedule(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            if (!enabled(context)) {
                scheduler.cancel(UPDATE_JOB)
            } else if (scheduler.getPendingJob(UPDATE_JOB) == null) {
                scheduler.schedule(JobInfo.Builder(UPDATE_JOB, ComponentName(context, UpdateCheckJob::class.java))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPeriodic(TimeUnit.DAYS.toMillis(1))
                    .setPersisted(true)
                    .build())
            }
        }
    }
}
