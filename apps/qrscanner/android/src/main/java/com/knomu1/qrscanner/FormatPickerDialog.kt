package com.knomu1.qrscanner

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

/** 読み取り対応バーコードの一覧と、読み取り対象にするかどうかの選択。 */
@Composable
fun FormatPickerDialog(
    enabledFormats: Set<Int>,
    onChange: ((ScanSettings) -> ScanSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.92f),
            shape = MaterialTheme.shapes.large,
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("読み取り対象のバーコード", style = MaterialTheme.typography.titleMedium)
                Text(
                    "有効 ${enabledFormats.size} / ${FORMAT_CATALOG.size} 形式。" +
                        "使わない形式を外すほど、別形式への誤読が減ります。",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 4.dp),
                )

                // 一括選択
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 4.dp), onClick = {
                        onChange { it.copy(enabledFormats = FORMAT_CATALOG.map { f -> f.format }.toSet()) }
                    }) { Text("全て", maxLines = 1) }
                    OutlinedButton(modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 4.dp), onClick = {
                        onChange {
                            it.copy(
                                enabledFormats = FORMAT_CATALOG
                                    .filter { f -> f.risk == MisreadRisk.Low }
                                    .map { f -> f.format }.toSet(),
                            )
                        }
                    }) { Text("低リスクのみ", maxLines = 1) }
                    OutlinedButton(modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 4.dp), onClick = {
                        onChange { it.copy(enabledFormats = setOf(ALWAYS_ON_FORMAT)) }
                    }) { Text("QR のみ", maxLines = 1) }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(FORMAT_CATALOG, key = { it.format }) { info ->
                        FormatRow(
                            info = info,
                            enabled = info.format in enabledFormats,
                            locked = info.format == ALWAYS_ON_FORMAT,
                            onToggle = { on ->
                                onChange { s ->
                                    val next = if (on) s.enabledFormats + info.format else s.enabledFormats - info.format
                                    s.copy(enabledFormats = next)
                                }
                            },
                        )
                        HorizontalDivider()
                    }
                }

                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("閉じる") }
            }
        }
    }
}

@Composable
private fun FormatRow(info: FormatInfo, enabled: Boolean, locked: Boolean, onToggle: (Boolean) -> Unit) {
    val riskColor = when (info.risk) {
        MisreadRisk.Low -> Color(0xFF1B8F3A)
        MisreadRisk.Medium -> Color(0xFFB26A00)
        MisreadRisk.High -> Color(0xFFC62828)
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                info.name + if (locked) "（常に有効）" else "",
                style = MaterialTheme.typography.titleSmall,
            )
            Text("${info.kind} ／ 用途: ${info.usage}", style = MaterialTheme.typography.bodySmall)
            Text("誤り検出: ${info.errorDetection}", style = MaterialTheme.typography.bodySmall)
            Text(
                "誤読リスク: ${info.risk.label}",
                style = MaterialTheme.typography.bodySmall,
                color = riskColor,
            )
        }
        Switch(checked = enabled, enabled = !locked, onCheckedChange = onToggle)
    }
}
