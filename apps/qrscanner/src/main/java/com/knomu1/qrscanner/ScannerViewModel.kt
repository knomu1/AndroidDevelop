package com.knomu1.qrscanner

import android.app.Application
import android.os.Build
import android.util.Log
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.mlkit.vision.barcode.common.Barcode
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.OffsetDateTime

/** 現在フレームで検出中のバーコード。corners は画像（回転補正済み）のピクセル座標。 */
data class Detection(val value: String, val format: Int, val corners: List<Offset>)

/** 検出結果とそれが得られた画像のサイズ（プレビュー表示への座標変換に使う）。 */
data class DetectionFrame(val detections: List<Detection> = emptyList(), val width: Int = 0, val height: Int = 0)

/** 履歴1件。同一内容は重複登録しない。 */
data class ScanItem(val value: String, val format: Int, val firstSeenAt: Long)

sealed interface ConnectionState {
    data object NotPaired : ConnectionState
    data class Connected(val name: String) : ConnectionState
    data class Unreachable(val name: String) : ConnectionState
    data class AuthFailed(val name: String) : ConnectionState
}

sealed interface PairingUiState {
    data object Idle : PairingUiState
    data class Confirm(val payload: PairingPayload) : PairingUiState
    data class Connecting(val payload: PairingPayload) : PairingUiState
    data class Failed(val payload: PairingPayload) : PairingUiState
}

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

class ScannerViewModel(application: Application) : AndroidViewModel(application) {
    private val settings = ServerSettings(application)
    private val api = ServerApi()

    private val _frame = MutableStateFlow(DetectionFrame())
    val frame: StateFlow<DetectionFrame> = _frame

    private val _history = MutableStateFlow<List<ScanItem>>(emptyList())
    val history: StateFlow<List<ScanItem>> = _history

    private val _connection = MutableStateFlow<ConnectionState>(ConnectionState.NotPaired)
    val connection: StateFlow<ConnectionState> = _connection

    private val _pairing = MutableStateFlow<PairingUiState>(PairingUiState.Idle)
    val pairing: StateFlow<PairingUiState> = _pairing

    private val _sentCount = MutableStateFlow(0)
    val sentCount: StateFlow<Int> = _sentCount

    private val _pendingCount = MutableStateFlow(0)
    val pendingCount: StateFlow<Int> = _pendingCount

    private class UploadJob(
        val jpeg: ByteArray,
        val width: Int,
        val height: Int,
        val codes: List<Detection>,
        val capturedAt: String,
    )

    private val queue = Channel<UploadJob>(MAX_QUEUE, BufferOverflow.DROP_OLDEST)
    private var currentUrl: String? = null
    private var ignorePairingUntil = 0L
    private val knownValues = HashSet<String>()

    init {
        settings.load()?.let { _connection.value = ConnectionState.Unreachable(it.name) }
        viewModelScope.launch {
            // 起動時: 保存済み接続先の疎通を確認する
            settings.load()?.let { refreshConnection(it) }
            for (job in queue) {
                deliver(job)
                _pendingCount.value = (_pendingCount.value - 1).coerceAtLeast(0)
            }
        }
    }

    val isPaired: Boolean get() = settings.load() != null

    /**
     * 1 フレーム分の検出結果を処理する。履歴に新規追加されたコードを返す（呼び出し側が画像を送信する）。
     * ペアリング用 QR は履歴・送信の対象にせず、接続確認のフローへ回す。
     */
    fun onBarcodes(barcodes: List<Barcode>, width: Int, height: Int): List<Detection> {
        val current = barcodes.mapNotNull { barcode ->
            val value = barcode.rawValue ?: return@mapNotNull null
            val corners = barcode.cornerPoints ?: return@mapNotNull null
            Detection(value, barcode.format, corners.map { Offset(it.x.toFloat(), it.y.toFloat()) })
        }
        _frame.value = DetectionFrame(current, width, height)
        if (current.isEmpty()) return emptyList()

        val (pairingCodes, scanCodes) = current.partition { PairingParser.isPairing(it.value) }
        pairingCodes.firstNotNullOfOrNull { PairingParser.parse(it.value) }?.let { offerPairing(it) }
        if (scanCodes.isEmpty()) return emptyList()

        val now = System.currentTimeMillis()
        val added: List<Detection>
        synchronized(knownValues) {
            added = scanCodes.distinctBy { it.value }.filter { knownValues.add(it.value) }
        }
        if (added.isEmpty()) return emptyList()

        _history.value = added.map { ScanItem(it.value, it.format, now) }.asReversed() + _history.value
        return added
    }

    /** 新規コードを画像と一緒に送信キューへ入れる（接続先が設定済みの場合のみ）。 */
    fun enqueueUpload(jpeg: ByteArray, width: Int, height: Int, codes: List<Detection>) {
        if (!isPaired) return
        _pendingCount.value += 1
        queue.trySend(UploadJob(jpeg, width, height, codes, OffsetDateTime.now().toString()))
    }

    private fun offerPairing(payload: PairingPayload) {
        if (System.currentTimeMillis() < ignorePairingUntil) return
        if (_pairing.value != PairingUiState.Idle) return
        if (settings.load() == payload && _connection.value is ConnectionState.Connected) return
        _pairing.value = PairingUiState.Confirm(payload)
    }

    fun confirmPairing() {
        val payload = (_pairing.value as? PairingUiState.Confirm)?.payload
            ?: (_pairing.value as? PairingUiState.Failed)?.payload
            ?: return
        _pairing.value = PairingUiState.Connecting(payload)
        viewModelScope.launch {
            val url = api.resolve(payload.urls)
            if (url == null) {
                Log.w(TAG, "Pairing failed: no candidate reachable: ${payload.urls}")
                _pairing.value = PairingUiState.Failed(payload)
            } else {
                Log.i(TAG, "Paired with ${payload.name} at $url")
                settings.save(payload)
                currentUrl = url
                _connection.value = ConnectionState.Connected(payload.name)
                _pairing.value = PairingUiState.Idle
            }
        }
    }

    fun cancelPairing() {
        ignorePairingUntil = System.currentTimeMillis() + PAIRING_IGNORE_MS
        _pairing.value = PairingUiState.Idle
    }

    fun forgetServer() {
        settings.clear()
        currentUrl = null
        _connection.value = ConnectionState.NotPaired
    }

    private suspend fun refreshConnection(payload: PairingPayload) {
        val url = api.resolve(payload.urls)
        currentUrl = url
        _connection.value =
            if (url != null) ConnectionState.Connected(payload.name) else ConnectionState.Unreachable(payload.name)
    }

    private suspend fun deliver(job: UploadJob) {
        val server = settings.load() ?: return
        repeat(MAX_ATTEMPTS) { attempt ->
            val url = currentUrl ?: api.resolve(server.urls).also { currentUrl = it }
            if (url != null) {
                val result = api.upload(
                    url, server.token, Build.MODEL, job.capturedAt,
                    job.width, job.height, job.jpeg, job.codes,
                )
                when (result) {
                    UploadResult.Ok -> {
                        _sentCount.value += 1
                        _connection.value = ConnectionState.Connected(server.name)
                        return
                    }
                    UploadResult.Unauthorized -> {
                        Log.w(TAG, "Upload rejected: token mismatch. Re-pairing required")
                        _connection.value = ConnectionState.AuthFailed(server.name)
                        return
                    }
                    is UploadResult.Failed -> {
                        Log.w(TAG, "Upload failed (attempt ${attempt + 1}/$MAX_ATTEMPTS): ${result.reason}")
                        currentUrl = null
                    }
                }
            } else {
                Log.w(TAG, "Server unreachable (attempt ${attempt + 1}/$MAX_ATTEMPTS)")
            }
            _connection.value = ConnectionState.Unreachable(server.name)
            delay(RETRY_BASE_MS shl attempt)
        }
        Log.e(TAG, "Dropped upload of ${job.codes.size} code(s) after $MAX_ATTEMPTS attempts")
    }

    fun clearHistory() {
        synchronized(knownValues) { knownValues.clear() }
        _history.value = emptyList()
    }

    private companion object {
        const val TAG = "ScanUpload"
        const val MAX_QUEUE = 50
        const val MAX_ATTEMPTS = 5
        const val RETRY_BASE_MS = 1000L
        const val PAIRING_IGNORE_MS = 5000L
    }
}
