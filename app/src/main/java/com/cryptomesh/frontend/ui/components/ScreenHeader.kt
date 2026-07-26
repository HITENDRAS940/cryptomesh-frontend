package com.cryptomesh.frontend.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun MainTabHeader(
    title: String,
    supportingText: String,
    modifier: Modifier = Modifier,
    trailingContent: (@Composable RowScope.() -> Unit)? = null
) {
    HeaderSurface(
        title = title,
        supportingText = supportingText,
        modifier = modifier,
        onBack = null,
        leadingContent = null,
        trailingContent = trailingContent
    )
}

@Composable
fun ScreenHeader(
    title: String,
    supportingText: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    leadingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable RowScope.() -> Unit)? = null
) {
    HeaderSurface(
        title = title,
        supportingText = supportingText,
        modifier = modifier,
        onBack = onBack,
        leadingContent = leadingContent,
        trailingContent = trailingContent
    )
}

@Composable
private fun HeaderSurface(
    title: String,
    supportingText: String,
    modifier: Modifier,
    onBack: (() -> Unit)?,
    leadingContent: (@Composable () -> Unit)?,
    trailingContent: (@Composable RowScope.() -> Unit)?
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 76.dp)
                    .padding(
                        start = if (onBack == null) 20.dp else 8.dp,
                        end = 12.dp,
                        top = 10.dp,
                        bottom = 10.dp
                    ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (onBack != null) {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
                if (leadingContent != null) {
                    leadingContent()
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = if (leadingContent != null) 12.dp else 4.dp)
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.headlineSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = supportingText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                trailingContent?.invoke(this)
            }
            HorizontalDivider()
        }
    }
}
