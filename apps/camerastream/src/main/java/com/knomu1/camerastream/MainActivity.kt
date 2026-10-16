package com.knomu1.camerastream

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
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
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private val vm: StreamViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleDebugIntent(intent)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    CameraStreamApp(vm)
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
     *   adb shell am start -n com.knomu1.camerastream/.MainActivity --es server_url http://PC:5080 --es token XXXX
     *   adb shell am start -n com.knomu1.camerastream/.MainActivity --ez disconnect true
     */
    private fun handleDebugIntent(intent: Intent?) {
        if (!BuildConfig.DEBUG || intent == null) return
        if (intent.getBooleanExtra("disconnect", false)) {
            Log.i("CameraStream", "debug intent: disconnect")
            vm.disconnect()
            return
        }
        val url = intent.getStringExtra("server_url")
        val token = intent.getStringExtra("token")
        if (!url.isNullOrBlank() && !token.isNullOrBlank()) {
            Log.i("CameraStream", "debug intent: connect to $url")
            vm.connectDirect(url, token)
        }
    }
}

@Composable
private fun CameraStreamApp(vm: StreamViewModel) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    // LAN への接続許可(Android 17 以降の実行時権限)。結果は問わず、拒否されても処理は続ける。
    val networkLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    DisposableEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 37 &&
            ContextCompat.checkSelfPermission(context, "android.permission.ACCESS_LOCAL_NETWORK") !=
            PackageManager.PERMISSION_GRANTED
        ) {
            networkLauncher.launch("android.permission.ACCESS_LOCAL_NETWORK")
        }
        onDispose { }
    }

    if (granted) {
        StreamScreen(vm)
    } else {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("カメラ映像を送るにはカメラの許可が必要です")
            Button(
                onClick = { cameraLauncher.launch(Manifest.permission.CAMERA) },
                modifier = Modifier.padding(top = 16.dp),
            ) { Text("カメラを許可する") }
        }
    }
}

@Composable
private fun StreamScreen(vm: StreamViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // 送信中は画面を消灯させない
    DisposableEffect(state.phase) {
        val window = (context as? ComponentActivity)?.window
        if (state.phase == Phase.Streaming) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    val controller = remember { LifecycleCameraController(context) }
    DisposableEffect(lifecycleOwner, controller) {
        val executor = Executors.newSingleThreadExecutor()
        val scanner = BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        )
        val recognizer = TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
        var lastSentAt = 0L
        var lastOcrAt = 0L

        controller.setEnabledUseCases(CameraController.IMAGE_ANALYSIS)
        controller.setImageAnalysisResolutionSelector(
            ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(
                    ResolutionStrategy(Size(960, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                )
                .build()
        )
        controller.setImageAnalysisAnalyzer(executor) { image ->
            if (vm.isStreaming) {
                val now = System.currentTimeMillis()
                val wantFrame = now - lastSentAt >= MIN_FRAME_INTERVAL_MS && vm.canSend()
                val wantOcr = now - lastOcrAt >= MIN_OCR_INTERVAL_MS && vm.canRecognize()
                if (!wantFrame && !wantOcr) {
                    image.close()
                    return@setImageAnalysisAnalyzer
                }
                try {
                    val upright = image.toUprightBitmap(image.imageInfo.rotationDegrees)
                    if (wantFrame) {
                        lastSentAt = now
                        vm.sendFrame(upright.width, upright.height, upright.toJpeg())
                    }
                    if (wantOcr) {
                        lastOcrAt = now
                        recognizer.process(InputImage.fromBitmap(upright, 0))
                            .addOnSuccessListener(executor) { text ->
                                val lines = text.textBlocks.flatMap { it.lines }.mapNotNull { line ->
                                    val pts = line.cornerPoints ?: return@mapNotNull null
                                    if (line.text.isBlank()) null
                                    else OcrLine(line.text, pts.map { it.x to it.y })
                                }
                                vm.sendOcr(upright.width, upright.height, lines)
                            }
                            .addOnFailureListener(executor) { e ->
                                Log.w("CameraStream", "OCR failed", e)
                                vm.releaseOcr()
                            }
                    }
                } catch (e: Exception) {
                    Log.w("CameraStream", "frame processing failed", e)
                    if (wantOcr) vm.releaseOcr()
                } finally {
                    image.close()
                }            } else {
                val media = image.image
                if (media == null) {
                    image.close()
                    return@setImageAnalysisAnalyzer
                }
                val input = InputImage.fromMediaImage(media, image.imageInfo.rotationDegrees)
                scanner.process(input)
                    .addOnSuccessListener(executor) { codes ->
                        codes.firstNotNullOfOrNull { it.rawValue }?.let(vm::onQr)
                    }
                    .addOnCompleteListener(executor) { image.close() }
            }
        }
        controller.bindToLifecycle(lifecycleOwner)
        onDispose {
            controller.unbind()
            controller.clearImageAnalysisAnalyzer()
            scanner.close()
            recognizer.close()
            executor.shutdown()
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
        Column(
            modifier = Modifier.fillMaxWidth().systemBarsPadding().background(Color(0xAA000000)).padding(12.dp),
        ) {
            Text(
                when (state.phase) {
                    Phase.Scanning -> "QR 待機中"
                    Phase.Connecting -> "接続中"
                    Phase.Streaming -> "PC へ送信中: ${state.serverName}"
                },
                color = Color.White,
            )
            Text(state.message, color = Color.White)
            if (state.phase == Phase.Streaming && state.ocrLines > 0) {
                Text("文字認識 ${state.ocrLines} 行: ${state.ocrText}", color = Color.Yellow, maxLines = 3)
            }
            if (state.phase == Phase.Streaming) {
                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text("${state.baseUrl}  送信 ${state.sent} / 失敗 ${state.failed}", color = Color.White)
                    Button(onClick = vm::disconnect) { Text("切断") }
                }
            }
        }
    }
}

private const val MIN_FRAME_INTERVAL_MS = 100L // 最大 約10fps
private const val MIN_OCR_INTERVAL_MS = 300L // 文字認識は最大 約3回/秒
private const val JPEG_QUALITY = 70

/** ImageProxy を回転補正した Bitmap にする。 */
private fun ImageProxy.toUprightBitmap(rotationDegrees: Int): Bitmap {
    val source = toBitmap()
    return if (rotationDegrees == 0) {
        source
    } else {
        Bitmap.createBitmap(
            source, 0, 0, source.width, source.height,
            Matrix().apply { postRotate(rotationDegrees.toFloat()) },
            true,
        )
    }
}

private fun Bitmap.toJpeg(): ByteArray = ByteArrayOutputStream().use { out ->
    compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
    out.toByteArray()
}