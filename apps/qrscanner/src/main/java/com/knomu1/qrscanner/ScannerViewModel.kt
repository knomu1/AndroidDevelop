package com.knomu1.qrscanner

import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.ViewModel
import com.google.mlkit.vision.barcode.common.Barcode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** 現在フレームで検出中のバーコード。corners はプレビュー View 座標系。 */
data class Detection(val value: String, val format: Int, val corners: List<Offset>)

/** 履歴1件。同一内容は重複登録しない。 */
data class ScanItem(val value: String, val format: Int, val firstSeenAt: Long)

fun formatLabel(format: Int): String = when (format) {
    Barcode.FORMAT_QR_CODE -> "QR"
    Barcode.FORMAT_EAN_13 -> "JAN-13"
    Barcode.FORMAT_EAN_8 -> "JAN-8"
    Barcode.FORMAT_UPC_A -> "UPC-A"
    Barcode.FORMAT_UPC_E -> "UPC-E"
    Barcode.FORMAT_CODE_128 -> "CODE128"
    Barcode.FORMAT_CODE_39 -> "CODE39"
    Barcode.FORMAT_CODE_93 -> "CODE93"
    Barcode.FORMAT_CODABAR -> "CODABAR"
    Barcode.FORMAT_ITF -> "ITF"
    Barcode.FORMAT_DATA_MATRIX -> "DataMatrix"
    Barcode.FORMAT_PDF417 -> "PDF417"
    Barcode.FORMAT_AZTEC -> "Aztec"
    else -> "その他"
}

class ScannerViewModel : ViewModel() {
    private val _detections = MutableStateFlow<List<Detection>>(emptyList())
    val detections: StateFlow<List<Detection>> = _detections

    private val _history = MutableStateFlow<List<ScanItem>>(emptyList())
    val history: StateFlow<List<ScanItem>> = _history

    fun onBarcodes(barcodes: List<Barcode>) {
        val current = barcodes.mapNotNull { barcode ->
            val value = barcode.rawValue ?: return@mapNotNull null
            val corners = barcode.cornerPoints ?: return@mapNotNull null
            Detection(value, barcode.format, corners.map { Offset(it.x.toFloat(), it.y.toFloat()) })
        }
        _detections.value = current
        if (current.isEmpty()) return

        val now = System.currentTimeMillis()
        _history.update { items ->
            val known = items.mapTo(HashSet()) { it.value }
            val added = current.distinctBy { it.value }
                .filter { it.value !in known }
                .map { ScanItem(it.value, it.format, now) }
            // 新しいものを先頭へ
            if (added.isEmpty()) items else added.asReversed() + items
        }
    }

    fun clearHistory() {
        _history.value = emptyList()
    }
}
