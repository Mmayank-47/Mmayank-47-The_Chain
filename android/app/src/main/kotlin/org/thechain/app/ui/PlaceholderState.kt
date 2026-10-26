package org.thechain.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.thechain.app.R

enum class ScreenState { Loading, Empty, Error, Content, Offline }

@Composable
fun ScreenScaffold(
    title: String,
    body: String,
    state: ScreenState = ScreenState.Empty,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium.copy(
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 36.sp,
            ),
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        when (state) {
            ScreenState.Loading -> {
                val cd = stringResource(R.string.state_loading)
                Column(
                    Modifier
                        .fillMaxSize()
                        .semantics { contentDescription = cd },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator(Modifier.size(48.dp))
                    Spacer(Modifier.height(12.dp))
                    Text(cd, fontSize = 14.sp)
                }
            }
            ScreenState.Empty -> StatusLine(stringResource(R.string.state_empty))
            ScreenState.Error -> StatusLine(stringResource(R.string.state_error))
            ScreenState.Offline -> StatusLine(stringResource(R.string.state_offline))
            ScreenState.Content -> Unit
        }
    }
}

@Composable
internal fun StatusLine(text: String) {
    Text(
        text = text,
        modifier = Modifier.semantics { contentDescription = text },
        style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp),
        color = MaterialTheme.colorScheme.onSurface,
    )
}
