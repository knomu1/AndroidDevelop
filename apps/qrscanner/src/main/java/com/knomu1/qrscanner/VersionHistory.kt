package com.knomu1.qrscanner

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private data class VersionEntry(val name: String, val code: Int, val date: String, val changes: List<String>)

private val versionHistory = listOf(
    VersionEntry(
        name = "0.3.0",
        code = 3,
        date = "2026-10-12",
        changes = listOf(
            "読み取り対象を ML Kit の全バーコード形式に拡大（CODE128 / ITF / DataMatrix など）",
            "履歴の形式表示を各形式名に対応",
        ),
    ),
    VersionEntry(
        name = "0.2.0",
        code = 2,
        date = "2026-10-12",
        changes = listOf(
            "JAN コード（EAN-13 / EAN-8）の読み取りに対応",
            "サンプル JAN バーコード表示を追加",
            "履歴に読み取り形式（QR / JAN）を表示",
            "バージョン履歴メニューを追加",
        ),
    ),
    VersionEntry(
        name = "0.1.0",
        code = 1,
        date = "2026-10-12",
        changes = listOf(
            "QR コードの複数同時読み取り",
            "検出したコードの枠表示",
            "読み取り履歴の一覧表示（重複排除）",
        ),
    ),
)

@Composable
fun VersionHistoryDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("バージョン履歴") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                versionHistory.forEach { entry ->
                    Text(
                        "v${entry.name} (${entry.code})  ${entry.date}",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    entry.changes.forEach { change ->
                        Text("・$change", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("閉じる") } },
    )
}
