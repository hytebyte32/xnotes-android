package com.xnotes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.xnotes.R
import com.xnotes.ui.icons.XnotesIcons
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor

private val TAB_GAP = 4.dp

/**
 * The thin strip of open documents above the canvas: tap a tab to switch, its cross to close it,
 * the plus for a fresh note. Press and hold a tab, then drag it sideways to reorder; the order is
 * saved. Hidden until a document is open.
 */
@Composable
fun TabStrip(editor: Editor, modifier: Modifier = Modifier) {
    if (editor.tabs.isEmpty()) return
    val palette = LocalPalette.current
    val active = editor.activeTabId
    val gap = with(LocalDensity.current) { TAB_GAP.toPx() }
    // Each tab's measured width, so a drag knows when it has passed its neighbour.
    val widths = remember { mutableMapOf<String, Float>() }
    var dragging by remember { mutableStateOf<String?>(null) }
    var dragX by remember { mutableFloatStateOf(0f) }
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
            for (tab in editor.tabs.toList()) key(tab.id) {
                val isActive = tab.id == active
                val isDragged = dragging == tab.id
                Row(
                    modifier = Modifier
                        .padding(end = TAB_GAP)
                        .zIndex(if (isDragged) 1f else 0f)
                        .graphicsLayer { translationX = if (isDragged) dragX else 0f }
                        .onGloballyPositioned { widths[tab.id] = it.size.width.toFloat() }
                        .height(28.dp)
                        .widthIn(min = 96.dp, max = 200.dp)
                        .then(if (isDragged) Modifier.shadow(6.dp, RoundedCornerShape(8.dp)) else Modifier)
                        .clip(RoundedCornerShape(8.dp))
                        .background((if (isActive) palette.surfaceHi else palette.surface).toComposeColor())
                        .alpha(if (isDragged) 0.92f else 1f)
                        .pointerInput(tab.id) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    dragging = tab.id
                                    dragX = 0f
                                },
                                onDragEnd = {
                                    dragging = null
                                    dragX = 0f
                                    editor.commitTabOrder()
                                },
                                onDragCancel = {
                                    dragging = null
                                    dragX = 0f
                                    editor.commitTabOrder()
                                },
                                onDrag = { change, amount ->
                                    change.consume()
                                    dragX += amount.x
                                    // Swap with a neighbour once the tab is more than halfway over it. The
                                    // list reflows under the finger, so the offset gives up that width.
                                    val i = editor.tabs.indexOfFirst { it.id == tab.id }
                                    if (i >= 0) {
                                        if (dragX > 0f && i < editor.tabs.lastIndex) {
                                            val w = (widths[editor.tabs[i + 1].id] ?: 0f) + gap
                                            if (w > gap && dragX > w / 2f) {
                                                editor.moveTab(i, i + 1)
                                                dragX -= w
                                            }
                                        } else if (dragX < 0f && i > 0) {
                                            val w = (widths[editor.tabs[i - 1].id] ?: 0f) + gap
                                            if (w > gap && -dragX > w / 2f) {
                                                editor.moveTab(i, i - 1)
                                                dragX += w
                                            }
                                        }
                                    }
                                },
                            )
                        }
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
