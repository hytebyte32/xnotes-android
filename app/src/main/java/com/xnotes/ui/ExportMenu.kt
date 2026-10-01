package com.xnotes.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.xnotes.R
import com.xnotes.ui.icons.XnotesIcons

/** What an export produces. A canvas only has [PDF]; a note can also hand out its pages as pictures. */
enum class ExportFormat { PDF, IMAGES }

/** The last choices made this session, so repeating an export is one tap on Share or Save. */
private object LastExport {
    var format = ExportFormat.PDF
    var allPages = true
}

/**
 * The toolbar's Export button: one popup with the format, the pages and the two things to do with
 * them. [onExport] gets (format, allPages, share): share hands the file to the system share sheet,
 * otherwise it is saved where the user picks. A canvas has no page choice and always exports a PDF,
 * paginated by the smart page-break planner.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ExportButton(isCanvas: Boolean, onExport: (ExportFormat, Boolean, Boolean) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var format by remember { mutableStateOf(if (isCanvas) ExportFormat.PDF else LastExport.format) }
    var allPages by remember { mutableStateOf(LastExport.allPages) }
    Box {
        ToolbarIcon(XnotesIcons.exportDoc, stringResource(R.string.toolbar_export), active = open) { open = true }
        if (open) {
            DropdownMenu(expanded = true, onDismissRequest = { open = false }) {
                Column(Modifier.width(260.dp).padding(horizontal = 14.dp, vertical = 8.dp)) {
                    PopupTitle(stringResource(R.string.toolbar_export))
                    if (!isCanvas) {
                        StyleCaption("Format")
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            ModeChip("PDF", format == ExportFormat.PDF) { format = ExportFormat.PDF }
                            ModeChip("Images (PNG)", format == ExportFormat.IMAGES) { format = ExportFormat.IMAGES }
                        }
                        Spacer(Modifier.size(10.dp))
                        StyleCaption("Pages")
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            ModeChip("All pages", allPages) { allPages = true }
                            ModeChip("This page", !allPages) { allPages = false }
                        }
                        Spacer(Modifier.size(12.dp))
                    } else {
                        StyleCaption("PDF, paged to fit: breaks fall between what you drew, never through it")
                        Spacer(Modifier.size(12.dp))
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ModeChip("Share", selected = true) { run(isCanvas, format, allPages, true, onExport) { open = false } }
                        ModeChip("Save to…", selected = false) { run(isCanvas, format, allPages, false, onExport) { open = false } }
                    }
                }
            }
        }
    }
}

private fun run(
    isCanvas: Boolean,
    format: ExportFormat,
    allPages: Boolean,
    share: Boolean,
    onExport: (ExportFormat, Boolean, Boolean) -> Unit,
    close: () -> Unit,
) {
    if (!isCanvas) {
        LastExport.format = format
        LastExport.allPages = allPages
    }
    close()
    onExport(if (isCanvas) ExportFormat.PDF else format, allPages, share)
}
