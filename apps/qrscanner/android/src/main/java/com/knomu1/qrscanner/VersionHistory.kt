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
        name = "0.5.1",
        code = 6,
        date = "2026-10-13",
        changes = listOf(
            "読み取り対応バーコードの一覧画面を追加（種類・誤り検出・誤読リスク・用途を表示）",
            "一覧から読み取り対象を選択（全て / 誤読しにくい形式のみ / QR のみ の一括選択つき）",
        ),
    ),
    VersionEntry(
        name = "0.5.0",
        code = 5,
        date = "2026-10-13",
        changes = listOf(
            "誤読対策: 複数フレームでの一致確認（N フレーム中 M 回一致で確定）を追加",
            "誤読対策: JAN/UPC のチェックデジット、ITF の桁数、短すぎる値の検証を追加",
            "読み取り設定: 形式ごとの有効/無効、検証・一致確認の設定を追加",
            "検出枠を状態で色分け（緑=確定 / 黄=確認中 / 赤=検証NG）し、集計を表示",
        ),
    ),
    VersionEntry(
        name = "0.4.0",
        code = 4,
        date = "2026-10-13",
        changes = listOf(
            "PC の接続用 QR を読み取って接続先を自動設定（アドレス入力不要）",
            "新規に読み取ったコードを画像付きで ScanMonitor サーバーへ自動送信",
            "接続状態の表示と接続解除メニューを追加",
        ),
    ),
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
