package org.thechain.app.ui

import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryStd
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Elderly
import androidx.compose.material.icons.filled.Flood
import androidx.compose.material.icons.filled.LunchDining
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.DateFormat
import java.util.Date
import org.thechain.app.ChainUiState
import org.thechain.app.RescuerProfileStore
import org.thechain.app.map.ChainMap
import org.thechain.app.CheckStatus
import org.thechain.app.R
import org.thechain.app.ui.theme.Amber
import org.thechain.app.ui.theme.AmberBg
import org.thechain.app.ui.theme.Brick
import org.thechain.app.ui.theme.CardBorder
import org.thechain.app.ui.theme.CardWhite
import org.thechain.app.ui.theme.Ink
import org.thechain.app.ui.theme.Muted
import org.thechain.app.ui.theme.Teal
import org.thechain.app.ui.theme.Verified
import org.thechain.app.ui.theme.VerifiedBg
import org.thechain.node.PhoneNode

private val CardShape = RoundedCornerShape(16.dp)
private val Pill = RoundedCornerShape(28.dp)

@Composable
fun FieldCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .border(1.5.dp, CardBorder, CardShape)
            .background(CardWhite, CardShape)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = { content() },
    )
}

@Composable
fun PrimaryPill(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = Pill,
        colors = ButtonDefaults.buttonColors(containerColor = Teal, contentColor = CardWhite),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .semantics { contentDescription = label },
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null)
        }
        Text(label, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = if (icon != null) 8.dp else 0.dp))
    }
}

@Composable
fun StatusChip(icon: ImageVector, label: String, tone: String) {
    val (bg, fg) = when (tone) {
        "ok" -> VerifiedBg to Verified
        "warn" -> AmberBg to Amber
        "bad" -> AmberBg to Brick
        else -> CardWhite to Ink
    }
    Row(
        Modifier
            .border(1.5.dp, fg, RoundedCornerShape(8.dp))
            .background(bg, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .semantics { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, contentDescription = null, tint = fg)
        Text(label, color = fg, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun HelpScreen(
    state: ChainUiState,
    onPreview: (String) -> Unit,
    onSubmit: (String) -> Unit,
) {
    val context = LocalContext.current
    var text by rememberSaveable { mutableStateOf("") }
    var step by rememberSaveable { mutableStateOf(0) }
    var micNote by rememberSaveable { mutableStateOf<String?>(null) }
    val micUnavailable = stringResource(R.string.help_mic_unavailable)
    val recognizer = remember {
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            SpeechRecognizer.createSpeechRecognizer(context)
        } else {
            null
        }
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startSpeech(recognizer, { text = it }, { micNote = micUnavailable })
        else micNote = micUnavailable
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.help_body), color = Muted, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Text(stringResource(R.string.help_step_badge, step + 1), color = Teal, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
        Text(stringResource(R.string.help_headline), style = MaterialTheme.typography.headlineLarge)
        when (step) {
            0 -> {
                NeedCards { prompt ->
                    text = prompt
                    onPreview(prompt)
                    step = 1
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "help_text" },
                    minLines = 3,
                    textStyle = MaterialTheme.typography.bodyLarge,
                    label = { Text(stringResource(R.string.help_hint)) },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            if (recognizer == null) micNote = micUnavailable
                            else micPermission.launch(android.Manifest.permission.RECORD_AUDIO)
                        },
                        modifier = Modifier.heightIn(min = 64.dp),
                    ) {
                        Icon(Icons.Filled.Mic, contentDescription = stringResource(R.string.help_mic))
                        Text(stringResource(R.string.help_mic), fontSize = 18.sp)
                    }
                }
                micNote?.let { Text(it, color = Muted, fontSize = 14.sp) }
                PrimaryPill(stringResource(R.string.help_next), enabled = text.isNotBlank(), onClick = {
                    onPreview(text)
                    step = 1
                })
            }
            1 -> {
                FieldCard {
                    Text(text, style = MaterialTheme.typography.bodyLarge)
                    val preview = state.pipeline
                    if (preview != null) {
                        Text(stringResource(R.string.help_kind, preview.kind), fontSize = 16.sp)
                        Text(
                            stringResource(R.string.help_features, preview.features.joinToString(", ").ifBlank { "—" }),
                            fontSize = 16.sp,
                        )
                        Text(stringResource(R.string.help_place, preview.locationText.ifBlank { "—" }), fontSize = 16.sp)
                        Text(stringResource(R.string.help_cell, preview.cell), fontSize = 16.sp)
                    }
                    GnssLine(state.gnssLat, state.gnssLon, state.gnssAccuracyM, state.gnssTimeMs)
                    Text(stringResource(R.string.help_privacy_exact), color = Muted, fontSize = 16.sp)
                }
                PrimaryPill(stringResource(R.string.help_send), onClick = {
                    onSubmit(text)
                    step = 2
                })
                OutlinedButton(
                    onClick = { step = 0 },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
                ) { Text(stringResource(R.string.help_edit), fontSize = 18.sp, color = Teal) }
            }
            else -> {
                val peers = state.verifiedPeers.size
                StatusChip(
                    icon = if (peers > 0) Icons.Filled.WifiTethering else Icons.Filled.Warning,
                    label = if (peers > 0) stringResource(R.string.help_broadcasting) else stringResource(R.string.help_held),
                    tone = if (peers > 0) "ok" else "warn",
                )
                Text(stringResource(R.string.help_sent_title), style = MaterialTheme.typography.headlineMedium)
                val line = justification(state.pipeline, state.lang)
                if (!line.isNullOrBlank()) {
                    FieldCard { Text(line, style = MaterialTheme.typography.bodyLarge) }
                }
                Text(stringResource(R.string.help_sent_body), color = Muted, fontSize = 16.sp)
                Text(stringResource(R.string.help_distress_window), color = Muted, fontSize = 15.sp)
                if (state.distressRemainingS > 0.0) {
                    StatusChip(
                        Icons.Filled.Warning,
                        stringResource(R.string.distress_timer, state.distressRemainingS.toInt()),
                        "warn",
                    )
                } else if (state.publicClaims.any { it.origin == state.nodeId && it.accepted }) {
                    StatusChip(Icons.Filled.CheckCircle, stringResource(R.string.distress_stopped), "ok")
                }
                PrimaryPill(stringResource(R.string.help_another), onClick = {
                    text = ""
                    step = 0
                })
            }
        }
    }
}

private fun justification(preview: PhoneNode.HelpPreview?, lang: String): String? = when (lang) {
    "hi" -> preview?.justificationHi
    "mr" -> preview?.justificationMr
    else -> preview?.justificationEn
}

@Composable
private fun NeedCards(onPick: (String) -> Unit) {
    val cards = listOf(
        Triple(R.string.cat_medicine, R.string.cat_medicine_desc, R.string.cat_medicine_prompt) to (Icons.Filled.MedicalServices to R.string.badge_urgent),
        Triple(R.string.cat_water, R.string.cat_water_desc, R.string.cat_water_prompt) to (Icons.Filled.WaterDrop to R.string.badge_critical),
        Triple(R.string.cat_rescue, R.string.cat_rescue_desc, R.string.cat_rescue_prompt) to (Icons.Filled.Flood to R.string.badge_immediate),
        Triple(R.string.cat_elderly, R.string.cat_elderly_desc, R.string.cat_elderly_prompt) to (Icons.Filled.Elderly to R.string.badge_high),
        Triple(R.string.cat_food, R.string.cat_food_desc, R.string.cat_food_prompt) to (Icons.Filled.LunchDining to R.string.badge_supply),
        Triple(R.string.cat_power, R.string.cat_power_desc, R.string.cat_power_prompt) to (Icons.Filled.Power to R.string.badge_support),
    )
    cards.forEach { (copy, badge) ->
        val (title, desc, prompt) = copy
        val (icon, badgeRes) = badge
        val promptText = stringResource(prompt)
        FieldCard(
            Modifier
                .clickable { onPick(promptText) }
                .semantics { contentDescription = promptText },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(icon, contentDescription = null, tint = Teal)
                Column(Modifier.weight(1f)) {
                    Text(stringResource(title), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text(stringResource(desc), color = Muted, fontSize = 16.sp)
                }
                StatusChip(Icons.Filled.Warning, stringResource(badgeRes), "warn")
            }
        }
    }
}

private fun startSpeech(
    recognizer: SpeechRecognizer?,
    onText: (String) -> Unit,
    onFail: () -> Unit,
) {
    if (recognizer == null) {
        onFail()
        return
    }
    recognizer.setRecognitionListener(object : RecognitionListener {
        override fun onResults(results: Bundle?) {
            val said = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (said.isNullOrBlank()) onFail() else onText(said)
        }
        override fun onError(error: Int) = onFail()
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onPartialResults(partialResults: Bundle?) = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    })
    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
    }
    runCatching { recognizer.startListening(intent) }.onFailure { onFail() }
}

@Composable
fun RespondScreen(state: ChainUiState, onAccept: (String) -> Unit) {
    var filter by rememberSaveable { mutableStateOf("all") }
    val claims = state.publicClaims.filter { claim ->
        when (filter) {
            "critical" -> claim.urgency >= 0.8
            "high" -> claim.urgency >= 0.5 && claim.urgency < 0.8
            "standard" -> claim.urgency < 0.5
            else -> true
        }
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.respond_headline), style = MaterialTheme.typography.headlineLarge, modifier = Modifier.weight(1f))
            Text(stringResource(R.string.respond_count, state.publicClaims.size), color = Teal, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
        Text(stringResource(R.string.respond_body), color = Muted, fontSize = 16.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("all" to R.string.filter_all, "critical" to R.string.filter_critical, "high" to R.string.filter_high, "standard" to R.string.filter_standard)
                .forEach { (key, label) ->
                    val name = stringResource(label)
                    OutlinedButton(
                        onClick = { filter = key },
                        modifier = Modifier.heightIn(min = 64.dp).semantics { contentDescription = name },
                    ) { Text(name, fontSize = 16.sp, fontWeight = if (filter == key) FontWeight.Bold else FontWeight.Normal) }
                }
        }
        if (claims.isEmpty()) {
            FieldCard {
                Text(stringResource(R.string.respond_empty_title), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text(stringResource(R.string.respond_empty_reason), color = Muted, fontSize = 16.sp)
            }
        } else {
            claims.forEach { claim -> ClaimCard(claim, state.lang, onAccept) }
        }
    }
}

@Composable
private fun ClaimCard(claim: PhoneNode.PublicClaimView, lang: String, onAccept: (String) -> Unit) {
    val line = when (lang) {
        "hi" -> claim.justificationHi
        "mr" -> claim.justificationMr
        else -> claim.justificationText
    } ?: listOfNotNull(claim.need, claim.category, claim.tag).joinToString(" · ")
    FieldCard {
        Text(line.ifBlank { claim.claimId.take(12) }, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Text(
            stringResource(R.string.help_cell, claim.cell ?: "?") + " · " + stringResource(R.string.respond_from, claim.origin.take(12)),
            color = Muted,
            fontSize = 16.sp,
        )
        GnssLine(claim.gnssLat, claim.gnssLon, claim.gnssAccuracyM, claim.gnssTimeMs)
        if (!claim.accepted) {
            PrimaryPill(stringResource(R.string.respond_accept), onClick = { onAccept(claim.claimId) })
        } else {
            val who = claim.acceptedByName?.takeIf { it.isNotBlank() }
                ?: claim.acceptedBy?.take(12)
                ?: "…"
            StatusChip(
                Icons.Filled.CheckCircle,
                stringResource(R.string.respond_ack_by, who),
                "ok",
            )
            claim.sealedHint?.let { Text(it, fontSize = 16.sp) }
        }
    }
}

@Composable
fun MapScreen(state: ChainUiState, onRequestLocation: () -> Unit, onDownloadMap: () -> Unit) {
    var layer by rememberSaveable { mutableStateOf("all") }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val cells = buildList {
        if (layer != "flood") state.publicClaims.mapNotNull { it.cell }.forEach { add(it) }
        if (layer != "distress") state.coarseCell?.let { add(it) }
    }.distinct()
    val pins = buildList {
        if (layer != "flood" && state.gnssLat != null && state.gnssLon != null) {
            add(state.gnssLat to state.gnssLon)
        }
        if (layer != "flood") {
            state.publicClaims.forEach { claim ->
                val lat = claim.gnssLat
                val lon = claim.gnssLon
                if (lat != null && lon != null) add(lat to lon)
            }
        }
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.map_headline), style = MaterialTheme.typography.headlineLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusChip(Icons.Filled.Place, stringResource(R.string.map_privacy), "ok")
            StatusChip(
                Icons.Filled.WaterDrop,
                stringResource(R.string.map_water, state.floodLevelM) + " · " + stringResource(R.string.map_no_rise),
                "warn",
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("all" to R.string.layer_all, "distress" to R.string.layer_distress, "flood" to R.string.layer_flood).forEach { (key, res) ->
                val name = stringResource(res)
                OutlinedButton(onClick = { layer = key }, modifier = Modifier.heightIn(min = 64.dp)) {
                    Text(name, fontWeight = if (layer == key) FontWeight.Bold else FontWeight.Normal, fontSize = 16.sp)
                }
            }
        }
        ChainMap(
            lat = state.gnssLat,
            lon = state.gnssLon,
            pins = pins,
            modifier = Modifier
                .fillMaxWidth()
                .height(360.dp)
                .border(1.5.dp, CardBorder, CardShape)
                .clickable { selected = cells.firstOrNull() },
        )
        when (state.mapStatus) {
            "downloading" -> Text(stringResource(R.string.map_downloading), color = Muted, fontSize = 16.sp)
            "failed" -> Text(stringResource(R.string.map_failed), color = Muted, fontSize = 16.sp)
            "no-fix" -> Text(stringResource(R.string.map_no_fix), color = Muted, fontSize = 16.sp)
            else -> if (state.mapTileCount > 0) {
                Text(stringResource(R.string.map_saved, state.mapTileCount), color = Muted, fontSize = 16.sp)
            }
        }
        PrimaryPill(
            stringResource(R.string.map_download),
            enabled = state.mapStatus != "downloading",
            onClick = onDownloadMap,
        )
        GnssLine(state.gnssLat, state.gnssLon, state.gnssAccuracyM, state.gnssTimeMs)
        if (!state.hasLocationFix) {
            FieldCard {
                Text(stringResource(R.string.map_location_off), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                state.coarseCell?.let { Text(stringResource(R.string.help_cell, it), fontSize = 16.sp) }
                PrimaryPill(stringResource(R.string.map_open_location), onClick = onRequestLocation)
            }
        } else {
            state.coarseCell?.let { Text(stringResource(R.string.help_cell, it), fontSize = 16.sp) }
        }
        selected?.let {
            FieldCard {
                Text(stringResource(R.string.map_cell_sheet, it), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text(stringResource(R.string.map_privacy), color = Muted, fontSize = 16.sp)
            }
        }
    }
}

@Composable
private fun GnssLine(lat: Double?, lon: Double?, accuracyM: Double?, timeMs: Long) {
    if (lat == null || lon == null) {
        Text(stringResource(R.string.gnss_waiting), color = Muted, fontSize = 16.sp)
        return
    }
    Text(stringResource(R.string.gnss_coords, lat, lon), fontWeight = FontWeight.Bold, fontSize = 18.sp)
    val whenText = if (timeMs > 0L) {
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(timeMs))
    } else {
        "—"
    }
    Text(stringResource(R.string.gnss_meta, accuracyM ?: 0.0, whenText), color = Muted, fontSize = 16.sp)
}

@Composable
fun MeshScreen(
    state: ChainUiState,
    onSaveContact: (String) -> Unit = {},
    onOpenChat: (String) -> Unit = {},
) {
    val onlineIds = state.verifiedPeers.map { it.nodeId }.toSet()
    val savedIds = state.savedContacts.map { it.nodeId }.toSet()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.mesh_headline), style = MaterialTheme.typography.headlineLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusChip(
                Icons.Filled.Bluetooth,
                if (state.advertising) stringResource(R.string.mesh_advertising) else stringResource(R.string.mesh_stopped),
                if (state.advertising) "ok" else "warn",
            )
            StatusChip(
                Icons.Filled.WifiTethering,
                if (state.discovering) stringResource(R.string.mesh_discovering) else stringResource(R.string.mesh_stopped),
                if (state.discovering) "ok" else "warn",
            )
        }
        if (state.radioStarted) {
            StatusChip(Icons.Filled.CheckCircle, stringResource(R.string.mesh_handshake_ready), "ok")
        }
        state.radioError?.let {
            StatusChip(Icons.Filled.Warning, stringResource(R.string.mesh_radio_error, it), "warn")
        }
        if (state.distressRemainingS > 0.0) {
            StatusChip(
                Icons.Filled.Warning,
                stringResource(R.string.distress_timer, state.distressRemainingS.toInt()),
                "warn",
            )
        }
        Text(stringResource(R.string.contacts_saved), style = MaterialTheme.typography.titleLarge)
        if (state.savedContacts.isEmpty()) {
            FieldCard {
                Text(stringResource(R.string.contacts_empty), color = Muted, fontSize = 16.sp)
            }
        } else {
            state.savedContacts.forEach { contact ->
                val online = contact.nodeId in onlineIds
                FieldCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(contact.name, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            Text(occupationLabel(LocalContext.current, contact.occupation), fontSize = 16.sp)
                            StatusChip(
                                if (online) Icons.Filled.CheckCircle else Icons.Filled.WifiTethering,
                                if (online) stringResource(R.string.contacts_online) else stringResource(R.string.contacts_offline),
                                if (online) "ok" else "warn",
                            )
                        }
                        val chatLabel = stringResource(R.string.chat_open)
                        Button(
                            onClick = { onOpenChat(contact.nodeId) },
                            shape = Pill,
                            colors = ButtonDefaults.buttonColors(containerColor = Teal, contentColor = CardWhite),
                            modifier = Modifier
                                .padding(start = 12.dp)
                                .heightIn(min = 48.dp)
                                .semantics { contentDescription = chatLabel },
                        ) {
                            Text(chatLabel, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
        val unsaved = state.peerCards.filter { it.nodeId !in savedIds }
        if (unsaved.isNotEmpty()) {
            Text(stringResource(R.string.contacts_nearby), style = MaterialTheme.typography.titleLarge)
            unsaved.forEach { card ->
                FieldCard {
                    Text(card.name, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text(occupationLabel(LocalContext.current, card.occupation), fontSize = 16.sp)
                    PrimaryPill(stringResource(R.string.contacts_save), onClick = { onSaveContact(card.nodeId) })
                }
            }
        }
        if (state.seenNames.isNotEmpty()) {
            Text(stringResource(R.string.mesh_seen), fontWeight = FontWeight.Bold, fontSize = 18.sp)
            state.seenNames.forEach { name ->
                FieldCard { Text(name, fontSize = 18.sp) }
            }
        }
        if (state.verifiedPeers.isEmpty() && state.seenNames.isEmpty() && state.savedContacts.isEmpty()) {
            FieldCard {
                Text(stringResource(R.string.mesh_waiting), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text(stringResource(R.string.respond_empty_reason), color = Muted, fontSize = 16.sp)
            }
        } else if (state.verifiedPeers.isNotEmpty()) {
            Text(stringResource(R.string.mesh_direct), style = MaterialTheme.typography.titleLarge)
            state.verifiedPeers.forEach { peer ->
                val card = state.peerCards.firstOrNull { it.nodeId == peer.nodeId }
                FieldCard {
                    Text(card?.name ?: peer.nodeId, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    if (card != null) {
                        Text(occupationLabel(LocalContext.current, card.occupation), fontSize = 16.sp)
                    } else {
                        Text(peer.nodeId, fontSize = 14.sp, color = Muted)
                    }
                    Text(stringResource(R.string.mesh_direct), fontSize = 16.sp)
                }
            }
        }
        Text(stringResource(R.string.mesh_this_phone), style = MaterialTheme.typography.titleLarge)
        FieldCard {
            Text(state.nodeId ?: "…", fontSize = 16.sp)
            Text(
                if (state.hardwareWrapped) stringResource(R.string.mesh_keystore_hw) else stringResource(R.string.mesh_keystore_sw),
                fontSize = 16.sp,
            )
            Text(stringResource(R.string.mesh_enrolled, state.enrolledPeerCount), fontSize = 16.sp)
            val drops = if (state.ingressDrops.isEmpty()) "0" else state.ingressDrops.entries.joinToString { "${it.key}=${it.value}" }
            Text(stringResource(R.string.mesh_drops, drops), fontSize = 16.sp)
            val pct = (state.batteryLevel * 100).toInt().coerceIn(0, 100)
            val charge = if (state.batteryCharging) stringResource(R.string.mesh_charging) else stringResource(R.string.mesh_not_charging)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.BatteryStd, contentDescription = null, tint = Teal)
                Text(stringResource(R.string.mesh_battery_row, pct, charge), fontSize = 16.sp)
            }
        }
    }
}

@Composable
fun MeScreen(
    state: ChainUiState,
    onOpenOemProtect: () -> Unit,
    onLanguage: (String) -> Unit,
    onSelfCheck: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.me_title), style = MaterialTheme.typography.headlineLarge)
        StatusChip(Icons.Filled.CheckCircle, stringResource(R.string.me_stored), "ok")
        val profile = RescuerProfileStore.load(LocalContext.current)
        if (profile != null) {
            FieldCard {
                Text(profile.name, fontWeight = FontWeight.Bold, fontSize = 22.sp)
                Text(occupationLabel(LocalContext.current, profile.occupation), fontSize = 18.sp)
                Text(stringResource(R.string.me_profile_blood, profile.bloodGroup), fontSize = 18.sp)
                if (profile.age.isNotBlank()) Text(stringResource(R.string.login_age) + " " + profile.age, fontSize = 16.sp)
                if (profile.phone.isNotBlank()) Text(profile.phone, fontSize = 16.sp)
                if (profile.emergencyPhone.isNotBlank()) {
                    Text(stringResource(R.string.me_profile_emergency, profile.emergencyName, profile.emergencyPhone), fontSize = 16.sp)
                }
                Text(stringResource(R.string.me_profile_allergies, profile.allergies), fontSize = 16.sp)
                Text(stringResource(R.string.me_profile_conditions, profile.conditions), fontSize = 16.sp)
                Text(
                    if (profile.biometric == "device") stringResource(R.string.me_profile_bio) else stringResource(R.string.me_profile_bio_missing),
                    fontSize = 16.sp,
                    color = Muted,
                )
            }
        }
        FieldCard {
            Text(state.nodeId ?: "…", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text(stringResource(R.string.me_body), color = Muted, fontSize = 16.sp)
        }
        Text(stringResource(R.string.me_lang), fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("en" to R.string.lang_en, "hi" to R.string.lang_hi, "mr" to R.string.lang_mr).forEach { (tag, res) ->
                val name = stringResource(res)
                OutlinedButton(
                    onClick = { onLanguage(tag) },
                    modifier = Modifier.heightIn(min = 64.dp).semantics { contentDescription = name },
                ) {
                    Text(name, fontWeight = if (state.lang == tag) FontWeight.Bold else FontWeight.Normal, fontSize = 18.sp, color = Teal)
                }
            }
        }
        val pct = (state.batteryLevel * 100).toInt().coerceIn(0, 100)
        val silence = if (state.timeToSilenceS.isFinite() && state.timeToSilenceS < 1e8) {
            val hours = (state.timeToSilenceS / 3600.0).toInt()
            stringResource(R.string.me_silence, "${hours}h")
        } else {
            stringResource(R.string.me_silence_unknown)
        }
        FieldCard {
            Text(stringResource(R.string.me_battery, pct), fontSize = 18.sp)
            Text(silence, fontSize = 16.sp, color = Muted)
        }
        PrimaryPill(stringResource(R.string.oem_open_from_me), onClick = onOpenOemProtect)
        PrimaryPill(
            if (state.selfCheck.running) stringResource(R.string.me_checking) else stringResource(R.string.me_check),
            enabled = !state.selfCheck.running,
            onClick = onSelfCheck,
        )
        state.selfCheck.rows.forEach { row ->
            val tone = when (row.status) {
                CheckStatus.PASS -> "ok"
                CheckStatus.OPEN -> "warn"
                CheckStatus.FAIL -> "bad"
            }
            FieldCard {
                StatusChip(
                    if (row.status == CheckStatus.PASS) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                    "${row.status} ${row.id}",
                    tone,
                )
                Text(row.detail, fontSize = 14.sp, color = Muted)
            }
        }
    }
}
