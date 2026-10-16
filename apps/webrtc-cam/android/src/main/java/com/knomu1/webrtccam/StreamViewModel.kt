package com.knomu1.webrtccam

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.webrtc.EglBase
import org.webrtc.SurfaceViewRenderer

enum class Phase { Scanning, Connecting, Streaming }

data class StreamUiState(
    val phase: Phase = Phase.Scanning,
    val serverName: String = "",
    val baseUrl: String = "",
    val message: String = "PC の画面に表示された QR を映してください",
    val connection: String = "",
    val stats: SenderStats? = null,
)

class StreamViewModel(app: Application) : AndroidViewModel(app) {
    private val settings = ServerSettings(app)
    private val saved = settings.load()

    private val _state = MutableStateFlow(
        if (saved != null) StreamUiState(phase = Phase.Connecting, message = "${saved.name} に接続中…") else StreamUiState()
    )
    val state: StateFlow<StreamUiState> = _state.asStateFlow()

    @Volatile private var session: WebRtcSession? = null
    private var signaling: SignalingClient? = null

    init {
        saved?.let { connect(it) }
    }

    fun onQr(raw: String) {
        if (_state.value.phase != Phase.Scanning) return
        val payload = PairingParser.parse(raw) ?: run {
            _state.update { it.copy(message = "接続用 QR ではありません") }
            return
        }
        connect(payload)
    }

    /** QR を使わずに接続先を直接指定する(デバッグビルドの adb 操作用)。 */
    fun connectDirect(url: String, token: String) = connect(PairingPayload("direct", token, listOf(url)))

    fun disconnect() {
        stopSession()
        settings.clear()
        _state.value = StreamUiState()
    }

    private var connectJob: Job? = null

    private fun connect(payload: PairingPayload) {
        connectJob?.cancel()
        stopSession()
        _state.value = StreamUiState(phase = Phase.Connecting, message = "${payload.name} に接続中…")
        connectJob = viewModelScope.launch {
            val url = ServerProbe.resolve(payload.urls)
            if (url == null) {
                Log.w(TAG, "No reachable URL among ${payload.urls}")
                _state.value = StreamUiState(message = "接続できません: ${payload.urls.joinToString()}")
                return@launch
            }
            settings.save(payload)
            startSession(url, payload)
        }
    }

    private suspend fun startSession(url: String, payload: PairingPayload) {
        val client = SignalingClient(
            url, payload.token,
            onMessage = { msg -> session?.onSignal(msg) },
            onStatus = { text -> _state.update { it.copy(message = text) } },
        )
        val s = WebRtcSession(
            getApplication(), client,
            onState = { text -> _state.update { it.copy(connection = text) } },
            onStats = { st -> _state.update { it.copy(stats = st) } },
        )
        signaling = client
        session = s
        _state.value = StreamUiState(phase = Phase.Streaming, serverName = payload.name, baseUrl = url, message = "PC のブラウザを待っています")
        // QR 読み取りの CameraX がカメラを手放すのを待ってから、WebRTC 側でカメラを開く
        delay(800)
        s.startCapture()
        client.connect()
    }

    private fun stopSession() {
        signaling?.close()
        signaling = null
        session?.release()
        session = null
    }

    /** 端末画面のプレビュー表示(省略可能)。 */
    fun attachPreview(renderer: SurfaceViewRenderer) {
        val s = session ?: return
        renderer.init(s.eglContext, null)
        renderer.setEnableHardwareScaler(true)
        s.videoTrack.addSink(renderer)
    }

    fun detachPreview(renderer: SurfaceViewRenderer) {
        runCatching { session?.videoTrack?.removeSink(renderer) }
        renderer.release()
    }

    override fun onCleared() {
        stopSession()
    }

    private companion object {
        const val TAG = "WebRtcCam"
    }
}