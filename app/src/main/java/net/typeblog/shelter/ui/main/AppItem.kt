package net.typeblog.shelter.ui.main

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.typeblog.shelter.data.model.AppInfo

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppItem(
    app: AppInfo,
    icon: Bitmap?,
    isSelected: Boolean,
    selectionOrder: Int? = null,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val containerColor = when {
        isSelected -> MaterialTheme.colorScheme.primaryContainer
        app.isHidden -> MaterialTheme.colorScheme.surfaceVariant
        else -> MaterialTheme.colorScheme.surface
    }

    Surface(
        color = containerColor,
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            ),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            if (icon != null) {
                Image(
                    bitmap = icon.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                )
            } else {
                Image(
                    imageVector = Icons.Default.Android,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (app.isHidden) "[${app.label}]" else app.label,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = app.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (app.isHidden) {
                Text(
                    text = "Frozen",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }

            if (app.isInAutoFreezeList) {
                Text(
                    text = "Auto",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }

            selectionOrder?.let { order ->
                Text(
                    text = order.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}
