package org.thechain.app

import android.content.Context
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters

/** WorkManager-only restart of [ChainForegroundService] — no networking. */
class RestartMeshWorker(
    context: Context,
    params: WorkerParameters,
) : Worker(context, params) {
    override fun doWork(): Result {
        Log.i(TAG, "restarting ChainForegroundService")
        ChainForegroundService.start(applicationContext)
        return Result.success()
    }

    companion object {
        const val UNIQUE_NAME = "org.thechain.app.restart_mesh"
        private const val TAG = "RestartMeshWorker"
    }
}
