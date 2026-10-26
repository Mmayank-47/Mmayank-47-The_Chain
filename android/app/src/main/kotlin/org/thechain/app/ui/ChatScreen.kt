package org.thechain.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.DateFormat
import java.util.Date
import org.thechain.app.ChainUiState
import org.thechain.app.R
import org.thechain.app.ui.theme.CardBorder
import org.thechain.app.ui.theme.CardWhite
import org.thechain.app.ui.theme.Cement
import org.thechain.app.ui.theme.Ink
import org.thechain.app.ui.theme.Muted
import org.thechain.app.ui.theme.Teal
import org.thechain.app.ui.theme.Verified

@Composable
fun ChatScreen(
    state: ChainUiState,
    peerId: String,
    onBack: () -> Unit,
    onSend: (String) -> Unit,
) {
    val context = LocalContext.current
    val contact = state.savedContacts.firstOrNull { it.nodeId == peerId }
    val title = contact?.name
        ?: state.peerCards.firstOrNull { it.nodeId == peerId }?.name
        ?: peerId.take(12)
    val online = state.verifiedPeers.any { it.nodeId == peerId }
    val occupation = contact?.occupation?.takeIf { it.isNotBlank() }?.let { occupationLabel(context, it) }.orEmpty()
    val presence = if (online) stringResource(R.string.contacts_online) else stringResource(R.string.contacts_offline)
    val subtitle = if (occupation.isBlank()) presence else "$presence · $occupation"
    val lines = state.chats.filter { it.peerId == peerId }
    val scroll = rememberScrollState()
    LaunchedEffect(lines.size, scroll.maxValue) {
        scroll.scrollTo(scroll.maxValue)
    }
    var draft by rememberSaveable(peerId) { mutableStateOf("") }
    val sendLabel = stringResource(R.string.chat_send)
    val backLabel = stringResource(R.string.chat_back)
    val canSend = draft.isNotBlank()

    Column(
        Modifier
            .fillMaxSize()
            .background(Cement)
            .imePadding(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .padding(start = 4.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = backLabel, tint = Teal)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    color = Ink,
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    subtitle,
                    color = if (online) Verified else Muted,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        HorizontalDivider(color = CardBorder)
        if (lines.isEmpty()) {
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    stringResource(R.string.chat_empty),
                    color = Ink,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                )
                Text(
                    stringResource(R.string.chat_mesh_note),
                    color = Muted,
                    fontSize = 15.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        } else {
            BoxWithConstraints(
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(scroll)
                        .heightIn(min = maxHeight)
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.Bottom),
                ) {
                    lines.forEach { line ->
                        val mine = line.fromId == state.nodeId
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
                        ) {
                            Column(
                                Modifier
                                    .widthIn(max = 300.dp)
                                    .clip(
                                        if (mine) {
                                            RoundedCornerShape(18.dp, 18.dp, 6.dp, 18.dp)
                                        } else {
                                            RoundedCornerShape(18.dp, 18.dp, 18.dp, 6.dp)
                                        },
                                    )
                                    .background(if (mine) Teal else CardWhite)
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                            ) {
                                Text(
                                    line.text,
                                    color = if (mine) CardWhite else Ink,
                                    fontSize = 17.sp,
                                    lineHeight = 22.sp,
                                )
                                if (line.timeMs > 0L) {
                                    Text(
                                        chatTime(line.timeMs),
                                        color = if (mine) CardWhite.copy(alpha = 0.85f) else Muted,
                                        fontSize = 12.sp,
                                        modifier = Modifier
                                            .align(Alignment.End)
                                            .padding(top = 2.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .background(CardWhite)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it.take(240) },
                modifier = Modifier.weight(1f),
                placeholder = { Text(stringResource(R.string.chat_hint), color = Muted) },
                shape = RoundedCornerShape(28.dp),
                minLines = 1,
                maxLines = 4,
                supportingText = if (draft.length >= 180) {
                    { Text("${draft.length}/240", color = Muted, fontSize = 12.sp) }
                } else {
                    null
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Teal,
                    unfocusedBorderColor = CardBorder,
                    focusedContainerColor = Cement,
                    unfocusedContainerColor = Cement,
                    cursorColor = Teal,
                ),
            )
            Box(
                Modifier
                    .padding(bottom = if (draft.length >= 180) 22.dp else 0.dp)
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(if (canSend) Teal else CardBorder)
                    .clickable(enabled = canSend, role = Role.Button) {
                        val text = draft.trim()
                        if (text.isEmpty()) return@clickable
                        onSend(text)
                        draft = ""
                    }
                    .semantics { contentDescription = sendLabel },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = null,
                    tint = if (canSend) CardWhite else Muted,
                )
            }
        }
    }
}

private fun chatTime(timeMs: Long): String =
    DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(timeMs))
