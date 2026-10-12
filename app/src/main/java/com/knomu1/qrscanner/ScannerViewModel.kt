package com.knomu1.qrscanner

import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.ViewModel
import com.google.mlkit.vision.barcode.common.Barcode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** 現在フレームで検出中のQR。corners はプレビュー View 座標系。 */
data class Detection(val value: String, val corners: List<Offset>)

/** 履歴1件。同一内容は重複登録しない。 */
data class ScanItem(val value: String, val firstSeenAt: Long)

class ScannerViewModel : ViewModel() {
    private val _detections = MutableStateFlow<List<Detection>>(emptyList())
    val detections: StateFlow<List<Detection>> = _detections

    private val _history = MutableStateFlow<List<ScanItem>>(emptyList())
    val history: StateFlow<List<ScanItem>> = _history

    fun onBarcodes(barcodes: List<Barcode>) {
        val current = barcodes.mapNotNull { barcode ->
            val value = barcode.rawValue ?: return@mapNotNull null
            val corners = barcode.cornerPoints ?: return@mapNotNull null
            Detection(value, corners.map { Offset(it.x.toFloat(), it.y.toFloat()) })
        }
        _detections.value = current
        if (current.isEmpty()) return

        val now = System.currentTimeMillis()
        _history.update { items ->
            val known = items.mapTo(HashSet()) { it.value }
            val added = current.map { it.value }.distinct()
                .filter { it !in known }
                .map { ScanItem(it, now) }
            // 新しいものを先頭へ
            if (added.isEmpty()) items else added.asReversed() + items
        }
    }

    fun clearHistory() {
        _history.value = emptyList()
    }
}
