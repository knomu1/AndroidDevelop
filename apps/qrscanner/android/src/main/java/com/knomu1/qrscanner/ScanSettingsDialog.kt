package com.knomu1.qrscanner

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

@Composable
fun ScanSettingsDialog(
    settings: ScanSettings,
    stats: ScanStats,
    onChange: ((ScanSettings) -> ScanSettings) -> Unit,
    onResetStats: () -> Unit,
    onOpenFormats: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.9f),
            shape = MaterialTheme.shapes.large,
        ) {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
                Text("読み取り設定", style = MaterialTheme.typography.titleMedium)

                SectionTitle("読み取り対象の形式")
                Text(
                    "有効 ${settings.enabledFormats.size} / ${FORMAT_CATALOG.size} 形式",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedButton(onClick = onOpenFormats) { Text("読み取り対象のバーコードを選ぶ") }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                SectionTitle("値の検証")
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()) {
                    Text("チェックデジット・桁数を検証", modifier = Modifier.weight(1f))
                    Switch(
                        checked = settings.validateValues,
                        onCheckedChange = { v -> onChange { it.copy(validateValues = v) } },
                    )
                }
                Text(
                    "JAN/UPC はチェックデジット、ITF は偶数桁と最小桁数を確認します。" +
                        "CODE39/CODABAR/CODE93 は最小桁数未満を部分読みとして除外します。",
                    style = MaterialTheme.typography.bodySmall,
                )
                Stepper(
                    label = "最小桁数",
                    value = settings.minLength,
                    range = 1..30,
                    enabled = settings.validateValues,
                    onValue = { v -> onChange { it.copy(minLength = v) } },
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                SectionTitle("複数フレームでの一致確認")
                Text(
                    "直近 N フレーム中 M 回以上同じ値が読めたときだけ確定します。" +
                        "N=1, M=1 にすると 1 回読めた時点で確定します（従来の動作）。",
                    style = MaterialTheme.typography.bodySmall,
                )
                Stepper(
                    label = "対象フレーム数 N",
                    value = settings.windowFrames,
                    range = 1..15,
                    onValue = { v ->
                        onChange { it.copy(windowFrames = v, requiredFrames = it.requiredFrames.coerceAtMost(v)) }
                    },
                )
                Stepper(
                    label = "必要な一致回数 M",
                    value = settings.effectiveRequired,
                    range = 1..settings.windowFrames,
                    onValue = { v -> onChange { it.copy(requiredFrames = v) } },
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                SectionTitle("集計")
                Text("確定: ${stats.confirmed}")
                Text("多数決で破棄: ${stats.discardedByVote}（一度は読めたが一致回数に届かず消えた候補）")
                Text("検証NG: ${stats.rejectedInvalid}（チェックデジット・桁数で除外した候補）")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    OutlinedButton(onClick = onResetStats) { Text("集計をリセット") }
                    TextButton(onClick = onDismiss) { Text("閉じる") }
                }
                Text(
                    "枠の色: 緑=確定 / 黄=一致確認中 / 赤=検証NG",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
}

@Composable
private fun Stepper(
    label: String,
    value: Int,
    range: IntRange,
    onValue: (Int) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label)
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { onValue((value - 1).coerceIn(range)) }, enabled = enabled && value > range.first) {
                Text("−")
            }
            Text("$value", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { onValue((value + 1).coerceIn(range)) }, enabled = enabled && value < range.last) {
                Text("＋")
            }
        }
    }
}
