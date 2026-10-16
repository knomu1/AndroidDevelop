package com.knomu1.webrtccam

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import org.json.JSONObject
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpParameters
import org.webrtc.RtpSender
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** PC のブラウザから指定される画質設定。 */
data class StreamConfig(
    val width: Int = 1280,
    val height: Int = 720,
    val fps: Int = 30,
    val bitrateKbps: Int = 5000,
    val codec: String = "H264",
)

/** 送信側の実測値(端末が見ている値)。PC の画面にも送る。 */
data class SenderStats(
    val width: Int = 0,
    val height: Int = 0,
    val fps: Double = 0.0,
    val bitrateKbps: Int = 0,
    val codec: String = "",
    val encoder: String = "",
    val hardware: Boolean = false,
    val encodeMs: Double = 0.0,
    val limitation: String = "",
    val batteryTempC: Double = 0.0,
)

/**
 * カメラ映像を WebRTC で送る一式。
 * 流れ: PC のブラウザが接続 → peer-joined を受信 → offer を作って送る → answer と ICE を受け取って接続。
 */
class WebRtcSession(
    private val context: Context,
    private val signaling: SignalingClient,
    private val onState: (String) -> Unit,
    private val onStats: (SenderStats) -> Unit,
) {
    // WebRTC の操作は 1 本のスレッドに集めて順序を保つ
    private val executor = Executors.newSingleThreadScheduledExecutor()
    private val eglBase = EglBase.create()
    val eglContext: EglBase.Context get() = eglBase.eglBaseContext

    private val factory: PeerConnectionFactory
    private val surfaceHelper: SurfaceTextureHelper
    private val videoSource: VideoSource
    val videoTrack: VideoTrack

    private var capturer: CameraVideoCapturer? = null
    private var pc: PeerConnection? = null
    private var videoSender: RtpSender? = null
    private var statsTask: ScheduledFuture<*>? = null
    private var prevBytes = 0L
    private var prevTimeMs = 0L
    private var prevEncodeTime = 0.0
    private var prevFramesEncoded = 0L

    @Volatile var config = StreamConfig()
        private set

    init {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context.applicationContext).createInitializationOptions()
        )
        factory = PeerConnectionFactory.builder()
            // 第2・3引数: Intel VP8 エンコーダを許可 / H.264 High プロファイルを許可
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()
        surfaceHelper = SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)
        videoSource = factory.createVideoSource(false)
        videoTrack = factory.createVideoTrack("video0", videoSource)
    }

    /** カメラを開く。CameraX(QR 読み取り)が解放された後に呼ぶ。 */
    fun startCapture() = executor.execute {
        val enumerator = Camera2Enumerator(context)
        val name = enumerator.deviceNames.firstOrNull { enumerator.isBackFacing(it) } ?: enumerator.deviceNames.first()
        val cap = enumerator.createCapturer(name, null)
        cap.initialize(surfaceHelper, context, videoSource.capturerObserver)
        cap.startCapture(config.width, config.height, config.fps)
        capturer = cap
        Log.i(TAG, "capture started: $name ${config.width}x${config.height}@${config.fps}")
    }

    /** シグナリングで届いたメッセージを処理する。 */
    fun onSignal(msg: JSONObject) = executor.execute {
        try {
            when (msg.getString("type")) {
                "peer-joined" -> startOffer()
                "answer" -> pc?.setRemoteDescription(SdpAdapter("setRemote(answer)"), SessionDescription(SessionDescription.Type.ANSWER, msg.getString("sdp")))
                "candidate" -> pc?.addIceCandidate(
                    IceCandidate(msg.optString("sdpMid"), msg.optInt("sdpMLineIndex"), msg.getString("candidate"))
                )
                "config" -> applyConfig(
                    StreamConfig(
                        msg.optInt("width", config.width), msg.optInt("height", config.height),
                        msg.optInt("fps", config.fps), msg.optInt("bitrateKbps", config.bitrateKbps),
                        msg.optString("codec", config.codec),
                    )
                )
                "peer-left" -> { closePc(); onState("PC のブラウザが切断しました") }
            }
        } catch (e: Exception) {
            Log.w(TAG, "signal handling failed: $msg", e)
        }
    }

    private fun startOffer() {
        closePc()
        val rtcConfig = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN // 同一 LAN なので STUN/TURN は使わない
        }
        val connection = factory.createPeerConnection(rtcConfig, observer) ?: return
        pc = connection

        val transceiver = connection.addTransceiver(
            videoTrack,
            RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY),
        )
        videoSender = transceiver.sender

        // 指定コーデックを優先する(offer の並び順で、ブラウザはその順に選ぶ)
        val codecs = factory.getRtpSenderCapabilities(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO).codecs
        val ordered = codecs.filter { it.name.equals(config.codec, true) } + codecs.filter { !it.name.equals(config.codec, true) }
        transceiver.setCodecPreferences(ordered)

        connection.createOffer(object : SdpAdapter("createOffer") {
            override fun onCreateSuccess(desc: SessionDescription) {
                connection.setLocalDescription(object : SdpAdapter("setLocal(offer)") {
                    override fun onSetSuccess() {
                        signaling.send(JSONObject().put("type", "offer").put("sdp", desc.description))
                        executor.execute { applyEncoderParams() }
                    }
                }, desc)
            }
        }, MediaConstraints())
        onState("接続中…")
    }

    private fun applyConfig(new: StreamConfig) {
        val old = config
        config = new
        Log.i(TAG, "config: $new")
        if (old.width != new.width || old.height != new.height || old.fps != new.fps) {
            capturer?.changeCaptureFormat(new.width, new.height, new.fps)
        }
        if (old.codec != new.codec && pc != null) startOffer() else applyEncoderParams()
    }

    private fun applyEncoderParams() {
        val sender = videoSender ?: return
        val params = sender.parameters
        if (params.encodings.isEmpty()) return
        val maxBps = config.bitrateKbps * 1000
        params.encodings[0].maxBitrateBps = maxBps
        params.encodings[0].minBitrateBps = maxBps / 2 // 回線が十分ある LAN で、画質が低いまま上がらないのを避ける
        params.encodings[0].maxFramerate = config.fps
        // 回線が足りないとき、解像度を保って fps を落とす(画質検証が目的のため)
        params.degradationPreference = RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION
        sender.setParameters(params)
    }

    private fun closePc() {
        statsTask?.cancel(false)
        statsTask = null
        pc?.close()
        pc = null
        videoSender = null
        prevBytes = 0
    }

    private val observer = object : PeerConnection.Observer {
        override fun onIceCandidate(c: IceCandidate) {
            Log.i(TAG, "local candidate: ${c.sdp}")
            signaling.send(
                JSONObject().put("type", "candidate").put("candidate", c.sdp)
                    .put("sdpMid", c.sdpMid).put("sdpMLineIndex", c.sdpMLineIndex)
            )
        }

        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
            Log.i(TAG, "connection: $newState")
            onState(
                when (newState) {
                    PeerConnection.PeerConnectionState.CONNECTED -> "配信中"
                    PeerConnection.PeerConnectionState.CONNECTING -> "接続中…"
                    PeerConnection.PeerConnectionState.FAILED -> "接続失敗"
                    PeerConnection.PeerConnectionState.DISCONNECTED -> "切断"
                    else -> newState.name
                }
            )
            if (newState == PeerConnection.PeerConnectionState.CONNECTED) executor.execute { startStats() }
        }

        override fun onSignalingChange(s: PeerConnection.SignalingState) {}
        override fun onIceConnectionChange(s: PeerConnection.IceConnectionState) { Log.i(TAG, "ice: $s") }
        override fun onIceConnectionReceivingChange(b: Boolean) {}
        override fun onIceGatheringChange(s: PeerConnection.IceGatheringState) {}
        override fun onIceCandidatesRemoved(c: Array<out IceCandidate>) {}
        override fun onAddStream(s: org.webrtc.MediaStream) {}
        override fun onRemoveStream(s: org.webrtc.MediaStream) {}
        override fun onDataChannel(d: org.webrtc.DataChannel) {}
        override fun onRenegotiationNeeded() {}
    }

    private fun startStats() {
        statsTask?.cancel(false)
        statsTask = executor.scheduleWithFixedDelay({ collectStats() }, 1, 1, TimeUnit.SECONDS)
    }

    private fun collectStats() {
        pc?.getStats { report ->
            val outbound = report.statsMap.values.firstOrNull {
                it.type == "outbound-rtp" && it.members["kind"] == "video"
            } ?: return@getStats
            val m = outbound.members
            fun num(key: String) = (m[key] as? Number)?.toDouble() ?: 0.0

            val nowMs = System.currentTimeMillis()
            val bytes = num("bytesSent").toLong()
            val kbps = if (prevTimeMs > 0 && nowMs > prevTimeMs) ((bytes - prevBytes) * 8.0 / (nowMs - prevTimeMs)).toInt() else 0
            prevBytes = bytes; prevTimeMs = nowMs

            val encodeTime = num("totalEncodeTime")
            val framesEncoded = num("framesEncoded").toLong()
            val df = framesEncoded - prevFramesEncoded
            val encodeMs = if (df > 0) (encodeTime - prevEncodeTime) / df * 1000 else 0.0
            prevEncodeTime = encodeTime; prevFramesEncoded = framesEncoded

            val codecId = m["codecId"] as? String
            val codec = codecId?.let { report.statsMap[it]?.members?.get("mimeType") as? String }.orEmpty()
            val encoder = m["encoderImplementation"] as? String ?: ""
            val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val tempC = (battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0

            val stats = SenderStats(
                width = num("frameWidth").toInt(), height = num("frameHeight").toInt(),
                fps = num("framesPerSecond"), bitrateKbps = kbps, codec = codec, encoder = encoder,
                hardware = (m["powerEfficientEncoder"] as? Boolean) ?: encoder.contains("MediaCodec", true),
                encodeMs = encodeMs, limitation = m["qualityLimitationReason"] as? String ?: "", batteryTempC = tempC,
            )
            val pair = report.statsMap.values.firstOrNull { it.type == "candidate-pair" && it.members["nominated"] == true }
            Log.i(TAG, "stats: ${stats.width}x${stats.height} ${"%.1f".format(stats.fps)}fps ${stats.bitrateKbps}kbps enc=${stats.encoder} limit=${stats.limitation} " +
                "sent=${bytes} pair=${pair?.members?.get("state")} pairBytesSent=${pair?.members?.get("bytesSent")} pairBytesRecv=${pair?.members?.get("bytesReceived")} rtt=${pair?.members?.get("currentRoundTripTime")} " +
                "framesEncoded=$framesEncoded")
            onStats(stats)
            signaling.send(
                JSONObject().put("type", "sender-stats")
                    .put("width", stats.width).put("height", stats.height)
                    .put("fps", Math.round(stats.fps * 10) / 10.0).put("bitrateKbps", stats.bitrateKbps)
                    .put("codec", stats.codec).put("encoder", stats.encoder).put("hardware", stats.hardware)
                    .put("encodeMs", Math.round(stats.encodeMs * 10) / 10.0)
                    .put("limitation", stats.limitation).put("batteryTempC", stats.batteryTempC)
            )
        }
    }

    fun release() {
        executor.execute {
            try {
                closePc()
                capturer?.stopCapture()
                capturer?.dispose()
                capturer = null
                videoTrack.dispose()
                videoSource.dispose()
                surfaceHelper.dispose()
                factory.dispose()
                eglBase.release()
            } catch (e: Exception) {
                Log.w(TAG, "release failed", e)
            }
            executor.shutdown()
        }
    }

    private open class SdpAdapter(private val label: String) : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String?) { Log.w(TAG, "$label create failed: $error") }
        override fun onSetFailure(error: String?) { Log.w(TAG, "$label set failed: $error") }
    }

    private companion object {
        const val TAG = "WebRtcCam"
    }
}