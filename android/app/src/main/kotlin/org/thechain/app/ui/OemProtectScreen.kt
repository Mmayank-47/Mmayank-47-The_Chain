package org.thechain.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.thechain.app.R
import org.thechain.app.oem.OemGuide

/**
 * Battery-opt rationale + OEM autostart steps. Shown after permissions (or from Me).
 * Deny path: user can Continue without exemption; Me tab can reopen this screen.
 */
@Composable
fun OemProtectScreen(
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val steps = remember { OemGuide.stepsFor() }
    var ignoring by remember { mutableStateOf(context.isIgnoringBatteryOptimizations()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                ignoring = context.isIgnoringBatteryOptimizations()
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    val titleRes = stringResByName(steps.titleKey) ?: R.string.oem_title_generic
    val requestLabel = stringResource(R.string.oem_battery_request)
    val continueLabel = if (ignoring) {
        stringResource(R.string.oem_continue_ok)
    } else {
        stringResource(R.string.oem_continue_denied)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(20.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.oem_protect_heading),
            style = MaterialTheme.typography.headlineMedium.copy(
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
            ),
        )
        Text(
            text = stringResource(R.string.oem_battery_rationale),
            fontSize = 16.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(
                R.string.oem_detected,
                Build.MANUFACTURER.orEmpty(),
                Build.BRAND.orEmpty(),
            ),
            fontSize = 14.sp,
        )
        Text(
            text = stringResource(titleRes),
            fontWeight = FontWeight.SemiBold,
            fontSize = 16.sp,
        )
        steps.stepKeys.forEachIndexed { index, key ->
            val res = stringResByName(key) ?: return@forEachIndexed
            Text(
                text = "${index + 1}. ${stringResource(res)}",
                fontSize = 14.sp,
            )
        }

        Spacer(Modifier.height(4.dp))

        if (!ignoring) {
            Button(
                onClick = { context.requestIgnoreBatteryOptimizations() },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp)
                    .semantics { contentDescription = requestLabel },
            ) {
                Text(requestLabel, fontSize = 16.sp)
            }
            Text(
                text = stringResource(R.string.oem_battery_deny_note),
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                text = stringResource(R.string.oem_battery_granted),
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        OutlinedButton(
            onClick = onContinue,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .semantics { contentDescription = continueLabel },
        ) {
            Text(continueLabel, fontSize = 16.sp)
        }
    }
}

@Composable
private fun stringResByName(name: String): Int? {
    val context = LocalContext.current
    val id = context.resources.getIdentifier(name, "string", context.packageName)
    return id.takeIf { it != 0 }
}

fun Context.isIgnoringBatteryOptimizations(): Boolean {
    val pm = getSystemService(PowerManager::class.java) ?: return true
    return pm.isIgnoringBatteryOptimizations(packageName)
}

fun Context.requestIgnoreBatteryOptimizations() {
    if (isIgnoringBatteryOptimizations()) return
    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
        data = Uri.parse("package:$packageName")
    }
    runCatching { startActivity(intent) }
}
