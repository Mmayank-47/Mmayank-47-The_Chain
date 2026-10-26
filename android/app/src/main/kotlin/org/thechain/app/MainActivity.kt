package org.thechain.app

import android.Manifest
import android.app.LocaleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.LocaleList
import android.provider.Settings
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import org.thechain.app.ui.ChainAppShell
import org.thechain.app.ui.LoginScreen
import org.thechain.app.ui.OemProtectScreen
import org.thechain.app.ui.PermissionsScreen
import org.thechain.app.ui.theme.TheChainTheme

class MainActivity : AppCompatActivity() {
    private var needsPermissions by mutableStateOf(true)
    private var permanentlyDenied by mutableStateOf(false)
    private var showShell by mutableStateOf(false)
    private var showOemProtect by mutableStateOf(false)
    private var unlocked by mutableStateOf(false)
    private var loginNote by mutableStateOf("")

    private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    private val fallbackUi = MutableStateFlow(ChainUiState())
    private var boundService: ChainForegroundService? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? ChainForegroundService.LocalBinder ?: return
            boundService = binder.service()
            binder.service().setLang(prefs.getString(KEY_LANG, "en") ?: "en")
            lifecycleScope.launch {
                binder.service().uiState.collect { fallbackUi.value = it }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            boundService = null
        }
    }

    private val locationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { startAndBind() }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val denied = result.filterValues { !it }.keys
        if (denied.isEmpty()) {
            needsPermissions = false
            permanentlyDenied = false
            onPermissionsReady()
        } else {
            permanentlyDenied = denied.any { !shouldShowRequestPermissionRationale(it) }
            needsPermissions = true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        needsPermissions = !hasAllRuntimePermissions()
        setContent {
            TheChainTheme {
                val ui by fallbackUi.collectAsStateWithLifecycle()
                when {
                    !unlocked -> {
                        LoginScreen(
                            note = loginNote,
                            onSaveAndUnlock = { draft -> saveWithBiometric(draft) },
                            onUnlockExisting = { unlockExisting() },
                            onEnrollBiometric = { openBiometricEnroll() },
                        )
                    }
                    needsPermissions && !showShell && !showOemProtect -> {
                        PermissionsScreen(
                            permanentlyDenied = permanentlyDenied,
                            onRequestPermissions = { requestRuntimePermissions() },
                            onContinue = {
                                showShell = true
                                maybeShowOemProtect()
                                startAndBind()
                            },
                        )
                    }
                    showOemProtect -> {
                        OemProtectScreen(
                            onContinue = {
                                prefs.edit().putBoolean(KEY_OEM_SEEN, true).apply()
                                showOemProtect = false
                                showShell = true
                                startAndBind()
                            },
                        )
                    }
                    else -> {
                        ChainAppShell(
                            state = ui,
                            onPreviewHelp = { text -> boundService?.previewHelp(text) },
                            onSubmitHelp = { text -> boundService?.submitHelp(text) },
                            onAcceptClaim = { id -> boundService?.acceptClaim(id) },
                            onSaveContact = { id -> boundService?.saveContact(id) },
                            onSendChat = { to, text -> boundService?.sendChat(to, text) },
                            onOpenOemProtect = { showOemProtect = true },
                            onLanguage = { tag -> applyLanguage(tag) },
                            onSelfCheck = { boundService?.runSelfCheck() },
                            onRequestLocation = { requestLocation() },
                            onDownloadMap = { boundService?.downloadMap() },
                        )
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (unlocked && (showShell || !needsPermissions)) {
            startAndBind()
        }
    }

    private fun saveWithBiometric(draft: RescuerProfile) {
        when (val status = BiometricLogin.status(this)) {
            BiometricManager.BIOMETRIC_SUCCESS -> BiometricLogin.prompt(
                this,
                onSuccess = {
                    RescuerProfileStore.save(this, draft.copy(biometric = "device"))
                    enterApp()
                },
                onError = { loginNote = it },
            )
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> openBiometricEnroll()
            else -> if (BiometricLogin.sensorMissing(status)) {
                RescuerProfileStore.save(this, draft.copy(biometric = "unavailable"))
                loginNote = getString(R.string.login_bio_missing)
                enterApp()
            } else {
                loginNote = getString(R.string.login_bio_unavailable)
            }
        }
    }

    private fun unlockExisting() {
        when (val status = BiometricLogin.status(this)) {
            BiometricManager.BIOMETRIC_SUCCESS -> BiometricLogin.prompt(
                this,
                onSuccess = { enterApp() },
                onError = { loginNote = it },
            )
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> openBiometricEnroll()
            else -> if (BiometricLogin.sensorMissing(status)) {
                enterApp()
            } else {
                loginNote = getString(R.string.login_bio_unavailable)
            }
        }
    }

    private fun openBiometricEnroll() {
        loginNote = getString(R.string.login_bio_none)
        runCatching { startActivity(BiometricLogin.enrollIntent()) }
    }

    private fun enterApp() {
        unlocked = true
        loginNote = ""
        if (!needsPermissions) {
            onPermissionsReady()
        }
    }

    override fun onStop() {
        runCatching { unbindService(connection) }
        boundService = null
        super.onStop()
    }

    private fun onPermissionsReady() {
        maybeShowOemProtect()
        if (!showOemProtect) {
            showShell = true
        }
        startAndBind()
    }

    private fun maybeShowOemProtect() {
        // One-shot after install/upgrade; Me → Autostart reopens. Deny of battery opt is allowed.
        showOemProtect = !prefs.getBoolean(KEY_OEM_SEEN, false)
    }

    private fun startAndBind() {
        ChainForegroundService.start(this)
        bindService(
            Intent(this, ChainForegroundService::class.java),
            connection,
            Context.BIND_AUTO_CREATE,
        )
    }

    private fun requestRuntimePermissions() {
        permissionLauncher.launch(requiredPermissions())
    }

    private fun hasAllRuntimePermissions(): Boolean =
        requiredPermissions().all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

    private fun applyLanguage(tag: String) {
        prefs.edit().putString(KEY_LANG, tag).apply()
        boundService?.setLang(tag)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(tag)
        } else {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
        }
    }

    private fun requestLocation() {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        if (fine != PackageManager.PERMISSION_GRANTED) {
            locationLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            )
        } else {
            startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        }
    }

    private fun requiredPermissions(): Array<String> {
        val list = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            list += Manifest.permission.BLUETOOTH_SCAN
            list += Manifest.permission.BLUETOOTH_ADVERTISE
            list += Manifest.permission.BLUETOOTH_CONNECT
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list += Manifest.permission.NEARBY_WIFI_DEVICES
            list += Manifest.permission.POST_NOTIFICATIONS
        }
        // Nearby Connections does not see peers on Samsung (and many other) devices
        // unless location permission is granted and location is on, including API 33+.
        list += Manifest.permission.ACCESS_FINE_LOCATION
        list += Manifest.permission.ACCESS_COARSE_LOCATION
        return list.toTypedArray()
    }

    companion object {
        private const val PREFS = "chain_oem"
        private const val KEY_OEM_SEEN = "oem_protect_seen"
        private const val KEY_LANG = "lang"
    }
}
