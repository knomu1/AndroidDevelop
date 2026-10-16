package com.knomu1.webrtccam

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

class MainActivity : ComponentActivity() {
    private val vm: StreamViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (BuildConfig.DEBUG) {
            // 検証用: 画面ロック中でもアプリを前面に出す(ロック中は Android がカメラ利用を止めるため)
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        // 画面が作り直されたとき(savedInstanceState あり)は、起動時のインテントを再処理しない。
        // 再処理すると、向きの変更などのたびに接続/切断が走ってしまう。
        if (savedInstanceState == null) handleDebugIntent(intent)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    WebRtcCamApp(vm)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDebugIntent(intent)
    }

    /**
     * QR 読み取りを省略するためのデバッグ用入口(デバッグビルドのみ)。
     *   adb shell am start -n com.knomu1.webrtccam/.MainActivity --es server_url http://PC:5080 --es token XXXX
     *   adb shell am start -n com.knomu1.webrtccam/.MainActivity --ez disconnect true
     */
    private fun handleDebugIntent(intent: Intent?) {
        if (!BuildConfig.DEBUG || intent == null) return
        if (intent.getBooleanExtra("disconnect", false)) {
            Log.i("WebRtcCam", "debug intent: disconnect")
            vm.disconnect()
            return
        }
        val url = intent.getStringExtra("server_url")
        val token = intent.getStringExtra("token")
        if (!url.isNullOrBlank() && !token.isNullOrBlank()) {
            Log.i("WebRtcCam", "debug intent: connect to $url")
            vm.connectDirect(url, token)
        }
    }
}

@Composable
private fun WebRtcCamApp(vm: StreamViewModel) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    val networkLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    DisposableEffect(Unit) {
        // LAN への接続許可(Android 17 以降の実行時権限)。拒否されても処理は続ける。
        if (Build.VERSION.SDK_INT >= 37 &&
            ContextCompat.checkSelfPermission(context, "android.permission.ACCESS_LOCAL_NETWORK") != PackageManager.PERMISSION_GRANTED
        ) {
            networkLauncher.launch("android.permission.ACCESS_LOCAL_NETWORK")
        }
        onDispose { }
    }

    if (!granted) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("カメラ映像を送るにはカメラの許可が必要です")
            Button(onClick = { cameraLauncher.launch(Manifest.permission.CAMERA) }, modifier = Modifier.padding(top = 16.dp)) {
                Text("カメラを許可する")
            }
        }
        return
    }

    val state by vm.state.collectAsStateWithLifecycle()
    when (state.phase) {
        Phase.Scanning -> ScanScreen(vm, state.message)
        Phase.Connecting -> CenterText(state.message)
        Phase.Streaming -> StreamScreen(vm)
    }
}

@Composable
private fun CenterText(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(text) }
}

/** 接続前: CameraX で QR を読む。接続が決まったらこの画面ごと破棄され、カメラは WebRTC 側へ渡る。 */
@Composable
private fun ScanScreen(vm: StreamViewModel, message: String) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val controller = remember { LifecycleCameraController(context) }

    DisposableEffect(lifecycleOwner, controller) {
        val pool = Executors.newSingleThreadExecutor()
        // QR を読み取ると、この画面がすぐ閉じられて pool が停止する。その後に ML Kit が結果を通知しても
        // RejectedExecutionException で落ちないよう、停止後に届いたタスクは握りつぶす。
        val executor = Executor { task ->
            try {
                pool.execute(task)
            } catch (_: RejectedExecutionException) {
            }
        }
        val scanner = BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        )
        controller.setEnabledUseCases(CameraController.IMAGE_ANALYSIS)
        controller.setImageAnalysisResolutionSelector(
            ResolutionSelector.Builder().setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY).build()
        )
        controller.setImageAnalysisAnalyzer(executor) { image ->
            val media = image.image
            if (media == null) {
                image.close()
                return@setImageAnalysisAnalyzer
            }
            scanner.process(InputImage.fromMediaImage(media, image.imageInfo.rotationDegrees))
                .addOnSuccessListener(executor) { codes -> codes.firstNotNullOfOrNull { it.rawValue }?.let(vm::onQr) }
                .addOnCompleteListener(executor) { image.close() }
        }
        controller.bindToLifecycle(lifecycleOwner)
        onDispose {
            controller.unbind()
            controller.clearImageAnalysisAnalyzer()
            scanner.close()
            pool.shutdown()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PreviewView(ctx).apply {
                    this.controller = controller
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                }
            },
        )
        Column(modifier = Modifier.fillMaxWidth().systemBarsPadding().background(Color(0xAA000000)).padding(12.dp)) {
            Text("QR 待機中", color = Color.White)
            Text(message, color = Color.White)
        }
    }
}

/** 配信中: WebRTC がカメラを使う。プレビューは省略でき、省くと端末の負荷(発熱)を比べられる。 */
@Composable
private fun StreamScreen(vm: StreamViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showPreview by remember { mutableStateOf(true) }

    DisposableEffect(Unit) {
        val window = (context as? ComponentActivity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (showPreview) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    SurfaceViewRenderer(ctx).apply {
                        setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                        vm.attachPreview(this)
                    }
                },
                onRelease = { vm.detachPreview(it) },
            )
        }
        Column(modifier = Modifier.fillMaxWidth().systemBarsPadding().background(Color(0xAA000000)).padding(12.dp)) {
            Text("WebRTC: ${state.connection.ifBlank { "待機中" }}  (${state.serverName})", color = Color.White)
            Text(state.message, color = Color.White)
            state.stats?.let { s ->
                Text(
                    "${s.width}x${s.height} ${"%.0f".format(s.fps)}fps ${s.bitrateKbps}kbps ${s.codec.removePrefix("video/")}",
                    color = Color.Yellow,
                )
                Text(
                    "${s.encoder}${if (s.hardware) "(HW)" else ""}  enc ${"%.1f".format(s.encodeMs)}ms  制限:${s.limitation}  ${"%.1f".format(s.batteryTempC)}℃",
                    color = Color.Yellow,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = showPreview, onCheckedChange = { showPreview = it })
                    Text(" プレビュー", color = Color.White)
                }
                Button(onClick = vm::disconnect) { Text("切断") }
            }
        }
    }
}