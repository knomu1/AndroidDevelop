package com.knomu1.camerastream

import android.app.Application
import android.os.Build
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

enum class Phase { Scanning, Connecting, Streaming }

data class StreamUiState(
    val phase: Phase = Phase.Scanning,
    val serverName: String = "",
    val baseUrl: String = "",
    val sent: Int = 0,
    val failed: Int = 0,
    val ocrLines: Int = 0,
    val ocrText: String = "",
    val message: String = "PC の画面に表示された QR を映してください",
)

class StreamViewModel(app: Application) : AndroidViewModel(app) {
    private val settings = ServerSettings(app)
    private val api = ServerApi()
    private val busy = AtomicBoolean(false)
    private val ocrBusy = AtomicBoolean(false)

    // 解析スレッドから参照するため @Volatile
    @Volatile private var target: Pair<String, String>? = null // baseUrl to token

    val deviceId: String = Build.MODEL.replace(' ', '_')

    private val _state = MutableStateFlow(StreamUiState())
    val state: StateFlow<StreamUiState> = _state.asStateFlow()

    val isStreaming: Boolean get() = target != null

    init {
        settings.load()?.let { connect(it) }
    }

    /** QR から読み取った文字列。接続情報なら接続を開始する。 */
    fun onQr(raw: String) {
        if (_state.value.phase != Phase.Scanning) return
        val payload = PairingParser.parse(raw) ?: run {
            _state.update { it.copy(message = "接続用 QR ではありません") }
            return
        }
        connect(payload)
    }

    /** QR を使わずに接続先を直接指定する(デバッグビルドの adb 操作用)。 */
    fun connectDirect(url: String, token: String) =
        connect(PairingPayload("direct", token, listOf(url)))

    fun disconnect() {
        target = null
        settings.clear()
        _state.value = StreamUiState()
    }

    private fun connect(payload: PairingPayload) {
        target = null
        _state.update { it.copy(phase = Phase.Connecting, message = "${payload.name} に接続中…") }
        viewModelScope.launch {
            val url = api.resolve(payload.urls)
            if (url == null) {
                Log.w(TAG, "No reachable URL among ${payload.urls}")
                _state.value = StreamUiState(message = "接続できません: ${payload.urls.joinToString()}")
                return@launch
            }
            Log.i(TAG, "Connected to $url")
            settings.save(payload)
            target = url to payload.token
            _state.value = StreamUiState(
                phase = Phase.Streaming, serverName = payload.name, baseUrl = url, message = "送信中",
            )
        }
    }

    /** 送信中でなければ true。解析スレッドから、JPEG 化の前に呼んで無駄な変換を避ける。 */
    fun canSend(): Boolean = target != null && !busy.get()

    /** OCR を始めてよいか(前回の認識と送信が終わっているか)。 */
    fun canRecognize(): Boolean = target != null && ocrBusy.compareAndSet(false, true)

    /** OCR を始められなかった/失敗したときに解放する。 */
    fun releaseOcr() = ocrBusy.set(false)

    fun sendOcr(width: Int, height: Int, lines: List<OcrLine>) {
        val (url, token) = target ?: run { ocrBusy.set(false); return }
        _state.update { it.copy(ocrLines = lines.size, ocrText = lines.joinToString(" / ") { l -> l.text }) }
        api.sendOcr(url, token, deviceId, width, height, lines) { result ->
            ocrBusy.set(false)
            if (result is SendResult.Unauthorized) disconnect()
            else if (result is SendResult.Failed) Log.w(TAG, "OCR send failed: ${result.reason}")
        }
    }

    fun sendFrame(width: Int, height: Int, jpeg: ByteArray) {
        val (url, token) = target ?: return
        if (!busy.compareAndSet(false, true)) return
        api.sendFrame(url, token, deviceId, width, height, jpeg) { result ->
            busy.set(false)
            when (result) {
                SendResult.Ok -> _state.update { it.copy(sent = it.sent + 1, message = "送信中") }
                SendResult.Unauthorized -> {
                    Log.w(TAG, "Unauthorized; clearing pairing")
                    disconnect()
                    _state.update { it.copy(message = "認証エラー。QR を読み直してください") }
                }
                is SendResult.Failed -> _state.update {
                    it.copy(failed = it.failed + 1, message = "送信失敗: ${result.reason}")
                }
            }
        }
    }

    private companion object {
        const val TAG = "CameraStream"
    }
}