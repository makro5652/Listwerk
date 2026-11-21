package app.listwerk.android.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.listwerk.android.R
import app.listwerk.core.TaskList

val FavoriteColor = Color(0xFFE6A700)

/** Listen umordnen (Pfeile) und Favorit wählen (Stern). Pfeile statt Ziehen: im Menü auf dem Handy zuverlässiger. */
@Composable
fun ListOrderDialog(vm: MainViewModel, lists: List<TaskList>, favorite: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.finish)) } },
        title = { Text(stringResource(R.string.arrange_lists)) },
        text = {
            Column {
                Text(stringResource(R.string.favorite_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyColumn(Modifier.heightIn(max = 420.dp).padding(top = 8.dp)) {
                    itemsIndexed(lists, key = { _, l -> l.href }) { i, l ->
                        val fav = l.href == favorite
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { vm.toggleFavorite(l.href) }) {
                                Icon(
                                    if (fav) Icons.Default.Star else Icons.Outlined.StarBorder, stringResource(R.string.favorite_list),
                                    tint = if (fav) FavoriteColor else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(l.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            IconButton(onClick = { vm.moveList(l, -1) }, enabled = i > 0) {
                                Icon(Icons.Default.KeyboardArrowUp, stringResource(R.string.move_up))
                            }
                            IconButton(onClick = { vm.moveList(l, 1) }, enabled = i < lists.lastIndex) {
                                Icon(Icons.Default.KeyboardArrowDown, stringResource(R.string.move_down))
                            }
                        }
                    }
                }
            }
        },
    )
}
