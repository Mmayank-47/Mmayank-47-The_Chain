package org.thechain.app.ui

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.thechain.app.BiometricLogin
import org.thechain.app.R
import org.thechain.app.RescuerProfile
import org.thechain.app.RescuerProfileStore
import org.thechain.app.ui.theme.CardBorder
import org.thechain.app.ui.theme.CardWhite
import org.thechain.app.ui.theme.Cement
import org.thechain.app.ui.theme.Ink
import org.thechain.app.ui.theme.Muted
import org.thechain.app.ui.theme.Teal

@Composable
fun LoginScreen(
    note: String,
    onSaveAndUnlock: (RescuerProfile) -> Unit,
    onUnlockExisting: () -> Unit,
    onEnrollBiometric: () -> Unit,
) {
    val context = LocalContext.current
    val stored = RescuerProfileStore.load(context)
    val bio = BiometricLogin.status(context)
    var editing by rememberSaveable { mutableStateOf(stored == null) }
    var name by rememberSaveable { mutableStateOf(stored?.name.orEmpty()) }
    var occupation by rememberSaveable { mutableStateOf(stored?.occupation.orEmpty()) }
    var blood by rememberSaveable { mutableStateOf(stored?.bloodGroup.orEmpty()) }
    var age by rememberSaveable { mutableStateOf(stored?.age.orEmpty()) }
    var sex by rememberSaveable { mutableStateOf(stored?.sex.orEmpty()) }
    var phone by rememberSaveable { mutableStateOf(stored?.phone.orEmpty()) }
    var emergencyName by rememberSaveable { mutableStateOf(stored?.emergencyName.orEmpty()) }
    var emergencyPhone by rememberSaveable { mutableStateOf(stored?.emergencyPhone.orEmpty()) }
    var allergies by rememberSaveable { mutableStateOf(stored?.allergies.orEmpty()) }
    var conditions by rememberSaveable { mutableStateOf(stored?.conditions.orEmpty()) }
    var formError by rememberSaveable { mutableStateOf("") }

    Column(
        Modifier
            .fillMaxSize()
            .background(Cement)
            .statusBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(Icons.Filled.Fingerprint, contentDescription = null, tint = Teal)
        Text(stringResource(R.string.login_title), fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Ink)
        Text(stringResource(R.string.login_subtitle), color = Muted, fontSize = 16.sp)

        if (stored != null && !editing) {
            FieldCard {
                Text(stored.name, fontWeight = FontWeight.Bold, fontSize = 22.sp)
                Text(occupationLabel(context, stored.occupation), fontSize = 18.sp)
                Text(stringResource(R.string.me_profile_blood, stored.bloodGroup), fontSize = 18.sp)
                if (stored.emergencyName.isNotBlank()) {
                    Text(
                        stringResource(R.string.login_emergency_line, stored.emergencyName, stored.emergencyPhone),
                        fontSize = 16.sp,
                        color = Muted,
                    )
                }
            }
            PrimaryPill(
                label = unlockLabel(bio),
                icon = Icons.Filled.Fingerprint,
                onClick = {
                    when {
                        bio == BiometricManager.BIOMETRIC_SUCCESS -> onUnlockExisting()
                        bio == BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> onEnrollBiometric()
                        BiometricLogin.sensorMissing(bio) -> onUnlockExisting()
                        else -> onUnlockExisting()
                    }
                },
            )
            TextButton(onClick = { editing = true }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text(stringResource(R.string.login_edit), color = Teal, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        } else {
            LabeledField(stringResource(R.string.login_name), name, { name = it })
            Text(stringResource(R.string.login_occupation), fontWeight = FontWeight.Bold, fontSize = 16.sp)
            ChoiceGrid(
                options = RescuerProfileStore.occupations,
                selected = occupation,
                label = { occupationLabel(context, it) },
                columns = 2,
                onSelect = { occupation = it },
            )
            Text(stringResource(R.string.login_blood), fontWeight = FontWeight.Bold, fontSize = 16.sp)
            ChoiceGrid(
                options = RescuerProfileStore.bloodGroups,
                selected = blood,
                label = { it },
                columns = 4,
                onSelect = { blood = it },
            )
            Text(stringResource(R.string.login_sex), fontWeight = FontWeight.Bold, fontSize = 16.sp)
            ChoiceGrid(
                options = RescuerProfileStore.sexes,
                selected = sex,
                label = { sexLabel(context, it) },
                columns = 3,
                onSelect = { sex = it },
            )
            LabeledField(stringResource(R.string.login_age), age, { age = it.filter(Char::isDigit).take(3) }, KeyboardType.Number)
            LabeledField(stringResource(R.string.login_phone), phone, { phone = it }, KeyboardType.Phone)
            LabeledField(stringResource(R.string.login_emergency_name), emergencyName, { emergencyName = it })
            LabeledField(stringResource(R.string.login_emergency_phone), emergencyPhone, { emergencyPhone = it }, KeyboardType.Phone)
            LabeledField(stringResource(R.string.login_allergies), allergies, { allergies = it }, hint = stringResource(R.string.login_hint_none))
            LabeledField(stringResource(R.string.login_conditions), conditions, { conditions = it }, hint = stringResource(R.string.login_hint_none))
            if (formError.isNotEmpty()) {
                Text(formError, color = org.thechain.app.ui.theme.Brick, fontSize = 16.sp)
            }
            PrimaryPill(
                label = saveLabel(bio),
                icon = Icons.Filled.Fingerprint,
                onClick = {
                    if (name.isBlank() || occupation.isBlank() || blood.isBlank() || emergencyPhone.isBlank()) {
                        formError = context.getString(R.string.login_required)
                        return@PrimaryPill
                    }
                    formError = ""
                    val draft = RescuerProfile(
                        name = name.trim(),
                        occupation = occupation,
                        bloodGroup = blood,
                        age = age.trim(),
                        sex = sex,
                        phone = phone.trim(),
                        emergencyName = emergencyName.trim(),
                        emergencyPhone = emergencyPhone.trim(),
                        allergies = allergies.trim(),
                        conditions = conditions.trim(),
                        biometric = "",
                    )
                    if (bio == BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED) {
                        onEnrollBiometric()
                    } else {
                        onSaveAndUnlock(draft)
                    }
                },
            )
        }
        if (note.isNotEmpty()) {
            Text(note, color = Muted, fontSize = 16.sp)
        }
    }
}

@Composable
private fun unlockLabel(bio: Int): String = when {
    bio == BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> stringResource(R.string.login_enroll)
    BiometricLogin.sensorMissing(bio) -> stringResource(R.string.login_continue)
    else -> stringResource(R.string.login_unlock)
}

@Composable
private fun saveLabel(bio: Int): String = when {
    bio == BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> stringResource(R.string.login_enroll)
    BiometricLogin.sensorMissing(bio) -> stringResource(R.string.login_save_no_bio)
    else -> stringResource(R.string.login_save)
}

@Composable
private fun LabeledField(
    label: String,
    value: String,
    onValue: (String) -> Unit,
    keyboard: KeyboardType = KeyboardType.Text,
    hint: String = "",
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        label = { Text(label) },
        placeholder = if (hint.isEmpty()) null else ({ Text(hint) }),
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ChoiceGrid(
    options: List<String>,
    selected: String,
    label: (String) -> String,
    columns: Int,
    onSelect: (String) -> Unit,
) {
    options.chunked(columns).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            row.forEach { option ->
                val text = label(option)
                val on = option == selected
                Text(
                    text,
                    color = if (on) CardWhite else Ink,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .background(if (on) Teal else CardWhite, RoundedCornerShape(12.dp))
                        .border(1.5.dp, if (on) Teal else CardBorder, RoundedCornerShape(12.dp))
                        .clickable { onSelect(option) }
                        .padding(horizontal = 8.dp, vertical = 12.dp)
                        .semantics { contentDescription = text },
                )
            }
            repeat(columns - row.size) {
                Text("", modifier = Modifier.weight(1f))
            }
        }
    }
}

internal fun occupationLabel(context: Context, code: String): String = when (code) {
    "doctor" -> context.getString(R.string.occ_doctor)
    "nurse" -> context.getString(R.string.occ_nurse)
    "paramedic" -> context.getString(R.string.occ_paramedic)
    "firefighter" -> context.getString(R.string.occ_firefighter)
    "police" -> context.getString(R.string.occ_police)
    "volunteer" -> context.getString(R.string.occ_volunteer)
    "other" -> context.getString(R.string.occ_other)
    else -> code
}

private fun sexLabel(context: Context, code: String): String = when (code) {
    "female" -> context.getString(R.string.sex_female)
    "male" -> context.getString(R.string.sex_male)
    "other" -> context.getString(R.string.sex_other)
    else -> code
}
