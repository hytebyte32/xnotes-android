package com.xnotes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xnotes.R
import com.xnotes.ui.icons.XnotesIcons
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor

/**
 * The thin strip of open documents above the canvas: tap a tab to switch, its cross to close it,
 * the plus for a fresh note. Hidden until a document is open.
 */
@Composable
fun TabStrip(editor: Editor, modifier: Modifier = Modifier) {
    if (editor.tabs.isEmpty()) return
    val palette = LocalPalette.current
    val active = editor.activeTabId
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(34.dp)
            .background(palette.panel.toComposeColor()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (tab in editor.tabs.toList()) {
                val isActive = tab.id == active
                Row(
                    modifier = Modifier
                        .padding(end = 4.dp)
                        .height(28.dp)
                        .widthIn(min = 96.dp, max = 200.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background((if (isActive) palette.surfaceHi else palette.surface).toComposeColor())
                        .clickable { editor.selectTab(tab.id) }
                        .padding(start = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        editor.tabLabel(tab),
                        modifier = Modifier.weight(1f),
                        color = (if (isActive) palette.text else palette.textDim).toComposeColor(),
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clickable { editor.closeTab(tab.id) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            XnotesIcons.close,
                            contentDescription = stringResource(R.string.tab_close),
                            tint = palette.textDim.toComposeColor(),
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            }
        }
        Box(
            modifier = Modifier
                .size(34.dp)
                .clickable { editor.newNote() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                XnotesIcons.plus,
                contentDescription = stringResource(R.string.tab_new),
                tint = palette.text.toComposeColor(),
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
