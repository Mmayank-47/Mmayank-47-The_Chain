package org.thechain.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager

/**
 * After reboot: try FGS immediately (BOOT_COMPLETED FGS exemption) and enqueue
 * [RestartMeshWorker] as a durable fallback when OEM delays the receiver.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON" &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) {
            return
        }
        Log.i(TAG, "boot action=$action — start FGS + enqueue RestartMeshWorker")
        val appCtx = context.applicationContext
        runCatching { ChainForegroundService.start(appCtx) }
            .onFailure { Log.w(TAG, "direct FGS start failed; WM fallback", it) }
        val work = OneTimeWorkRequestBuilder<RestartMeshWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(appCtx).enqueueUniqueWork(
            RestartMeshWorker.UNIQUE_NAME,
            ExistingWorkPolicy.REPLACE,
            work,
        )
    }

    companion object {
        private const val TAG = "ChainBoot"
    }
}
