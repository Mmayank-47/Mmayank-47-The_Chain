package org.thechain.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.VolunteerActivism
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.thechain.app.BuildConfig
import org.thechain.app.ChainUiState
import org.thechain.app.R
import org.thechain.app.ui.theme.CardWhite
import org.thechain.app.ui.theme.Cement
import org.thechain.app.ui.theme.Ink
import org.thechain.app.ui.theme.Muted
import org.thechain.app.ui.theme.Teal

enum class Tab { Help, Respond, Map, Mesh, Me }

@Composable
fun ChainAppShell(
    state: ChainUiState,
    onPreviewHelp: (String) -> Unit,
    onSubmitHelp: (String) -> Unit,
    onAcceptClaim: (String) -> Unit,
    onSaveContact: (String) -> Unit = {},
    onSendChat: (String, String) -> Unit = { _, _ -> },
    onOpenOemProtect: () -> Unit = {},
    onLanguage: (String) -> Unit = {},
    onSelfCheck: () -> Unit = {},
    onRequestLocation: () -> Unit = {},
    onDownloadMap: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var tab by rememberSaveable { mutableStateOf(Tab.Help) }
    var chatPeer by rememberSaveable { mutableStateOf<String?>(null) }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = Cement,
        bottomBar = {
            if (chatPeer == null || tab != Tab.Mesh) {
                ChainBottomNav(selected = tab, onSelect = {
                    tab = it
                    chatPeer = null
                })
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .statusBarsPadding()
                .background(Cement),
        ) {
            if (BuildConfig.DEV_COMPONENTS) DevBadge()
            Box(Modifier.weight(1f)) {
                val peer = chatPeer
                if (tab == Tab.Mesh && peer != null) {
                    ChatScreen(
                        state = state,
                        peerId = peer,
                        onBack = { chatPeer = null },
                        onSend = { text -> onSendChat(peer, text) },
                    )
                } else when (tab) {
                    Tab.Help -> HelpScreen(state, onPreviewHelp, onSubmitHelp)
                    Tab.Respond -> RespondScreen(state, onAcceptClaim)
                    Tab.Map -> MapScreen(state, onRequestLocation, onDownloadMap)
                    Tab.Mesh -> MeshScreen(state, onSaveContact) { chatPeer = it }
                    Tab.Me -> MeScreen(state, onOpenOemProtect, onLanguage, onSelfCheck)
                }
            }
        }
    }
}

@Composable
private fun DevBadge() {
    val label = stringResource(R.string.dev_build_badge)
    Row(
        Modifier
            .padding(12.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF895100))
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .semantics { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    }
}

@Composable
private fun ChainBottomNav(selected: Tab, onSelect: (Tab) -> Unit) {
    val items = listOf(
        Tab.Help to Triple(R.string.nav_help, R.string.nav_help_cd, Icons.Filled.VolunteerActivism),
        Tab.Respond to Triple(R.string.nav_respond, R.string.nav_respond_cd, Icons.Outlined.Campaign),
        Tab.Map to Triple(R.string.nav_map, R.string.nav_map_cd, Icons.Filled.Map),
        Tab.Mesh to Triple(R.string.nav_mesh, R.string.nav_mesh_cd, Icons.Filled.WifiTethering),
        Tab.Me to Triple(R.string.nav_me, R.string.nav_me_cd, Icons.Filled.Person),
    )
    NavigationBar(
        modifier = Modifier.navigationBarsPadding().heightIn(min = 72.dp),
        containerColor = CardWhite,
        contentColor = Ink,
    ) {
        items.forEach { (tab, spec) ->
            val (labelRes, cdRes, icon) = spec
            val label = stringResource(labelRes)
            val cd = stringResource(cdRes)
            NavigationBarItem(
                selected = selected == tab,
                onClick = { onSelect(tab) },
                icon = { Icon(icon, contentDescription = null) },
                label = { Text(label, fontSize = 14.sp, maxLines = 1) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = Teal,
                    selectedTextColor = Teal,
                    unselectedIconColor = Muted,
                    unselectedTextColor = Muted,
                    indicatorColor = Color(0xFFA6EFF0),
                ),
                modifier = Modifier.heightIn(min = 64.dp).semantics { contentDescription = cd },
            )
        }
    }
}
