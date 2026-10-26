package org.thechain.app.ui

import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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

@Composable
fun PermissionsScreen(
    permanentlyDenied: Boolean,
    onRequestPermissions: () -> Unit,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var radios by remember { mutableStateOf(RadioStatus.from(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                radios = RadioStatus.from(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.permissions_title),
            style = MaterialTheme.typography.headlineMedium.copy(
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
            ),
        )
        Text(
            text = stringResource(R.string.permissions_body),
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (permanentlyDenied) {
            Text(
                text = stringResource(R.string.permissions_denied_permanent),
                color = MaterialTheme.colorScheme.error,
                fontSize = 14.sp,
            )
        }
        Spacer(Modifier.height(8.dp))

        val grantLabel = if (permanentlyDenied) {
            stringResource(R.string.permissions_open_settings)
        } else {
            stringResource(R.string.permissions_grant)
        }
        Button(
            onClick = {
                if (permanentlyDenied) {
                    context.openAppSettings()
                } else {
                    onRequestPermissions()
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .semantics { contentDescription = grantLabel },
        ) {
            Text(grantLabel, fontSize = 16.sp)
        }

        if (!radios.bluetoothOn) {
            RadioButton(
                label = stringResource(R.string.permissions_bt_off),
                onClick = {
                    context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                },
            )
        }
        if (!radios.wifiOn) {
            RadioButton(
                label = stringResource(R.string.permissions_wifi_off),
                onClick = {
                    context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
                },
            )
        }
        if (!radios.locationOn) {
            RadioButton(
                label = stringResource(R.string.permissions_location_off),
                onClick = {
                    context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                },
            )
        }

        OutlinedButton(
            onClick = onContinue,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp),
        ) {
            Text(stringResource(R.string.permissions_continue), fontSize = 16.sp)
        }
    }
}

@Composable
private fun RadioButton(label: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .semantics { contentDescription = label },
    ) {
        Text(label, fontSize = 16.sp)
    }
}

private data class RadioStatus(
    val bluetoothOn: Boolean,
    val wifiOn: Boolean,
    val locationOn: Boolean,
) {
    companion object {
        fun from(context: Context): RadioStatus {
            val bt = context.getSystemService(BluetoothManager::class.java)
                ?.adapter?.isEnabled == true
            @Suppress("DEPRECATION")
            val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
                ?.isWifiEnabled == true
            val lm = context.getSystemService(LocationManager::class.java)
            val loc = lm?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true ||
                lm?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true
            return RadioStatus(bt, wifi, loc)
        }
    }
}

private fun Context.openAppSettings() {
    startActivity(
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null),
        ),
    )
}
