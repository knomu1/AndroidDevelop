package com.knomu1.qrscanner

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageAnalysis
import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import android.util.Size
import com.google.mlkit.vision.common.InputImage
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import androidx.compose.material3.AlertDialog
import androidx.compose.ui.geometry.Offset
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import kotlin.math.max
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    QrApp()
                }
            }
        }
    }
}

@Composable
private fun QrApp(vm: ScannerViewModel = viewModel()) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
    }

    if (granted) {
        ScannerScreen(vm)
    } else {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("QRを読み取るにはカメラの許可が必要です")
            Button(
                onClick = { launcher.launch(Manifest.permission.CAMERA) },
                modifier = Modifier.padding(top = 16.dp),
            ) { Text("カメラを許可する") }
        }
    }
}

@Composable
private fun ScannerScreen(vm: ScannerViewModel) {
    val frame by vm.frame.collectAsStateWithLifecycle()
    val connection by vm.connection.collectAsStateWithLifecycle()
    val pairing by vm.pairing.collectAsStateWithLifecycle()
    val sentCount by vm.sentCount.collectAsStateWithLifecycle()
    val pendingCount by vm.pendingCount.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    var showSamples by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showVersionHistory by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("QR Scanner v${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleSmall)
            Box {
                TextButton(onClick = { showMenu = true }) { Text("メニュー") }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("バージョン履歴") },
                        onClick = {
                            showMenu = false
                            showVersionHistory = true
                        },
                    )
                }
            }
        }
        ConnectionBar(
            connection = connection,
            sentCount = sentCount,
            pendingCount = pendingCount,
            onForget = vm::forgetServer,
        )
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            CameraPreview(vm)
            // 検出したバーコードの枠（画像座標 → FILL_CENTER のプレビュー座標へ変換）
            Canvas(modifier = Modifier.fillMaxSize()) {
                if (frame.width == 0 || frame.height == 0) return@Canvas
                val scale = max(size.width / frame.width, size.height / frame.height)
                val dx = (size.width - frame.width * scale) / 2f
                val dy = (size.height - frame.height * scale) / 2f
                fun map(p: Offset) = Offset(p.x * scale + dx, p.y * scale + dy)
                frame.detections.forEach { detection ->
                    if (detection.corners.size < 3) return@forEach
                    val corners = detection.corners.map(::map)
                    val path = Path().apply {
                        moveTo(corners.first().x, corners.first().y)
                        corners.drop(1).forEach { lineTo(it.x, it.y) }
                        close()
                    }
                    drawPath(path, Color(0xFF00E676), style = Stroke(width = 6f))
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("履歴 ${history.size} 件", style = MaterialTheme.typography.titleMedium)
            Row {
                TextButton(onClick = { showSamples = true }) { Text("サンプル") }
                TextButton(onClick = vm::clearHistory, enabled = history.isNotEmpty()) { Text("クリア") }
            }
        }
        HorizontalDivider()
        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
            items(history, key = { it.value }) { item ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(item.value, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "${formatLabel(item.format)}  ${timeFormat.format(Date(item.firstSeenAt))}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                HorizontalDivider()
            }
        }
    }

    PairingDialog(
        state = pairing,
        onConfirm = vm::confirmPairing,
        onCancel = vm::cancelPairing,
    )
    if (showSamples) {
        SampleBarcodeDialog(onDismiss = { showSamples = false })
    }
    if (showVersionHistory) {
        VersionHistoryDialog(onDismiss = { showVersionHistory = false })
    }
}

@Composable
private fun ConnectionBar(
    connection: ConnectionState,
    sentCount: Int,
    pendingCount: Int,
    onForget: () -> Unit,
) {
    val (label, color) = when (connection) {
        ConnectionState.NotPaired -> "未接続: PCの接続用QRを映してください" to Color(0xFF757575)
        is ConnectionState.Connected -> "接続中: ${connection.name}" to Color(0xFF1B8F3A)
        is ConnectionState.Unreachable -> "接続できません: ${connection.name}" to Color(0xFFC62828)
        is ConnectionState.AuthFailed -> "認証エラー: ${connection.name}（QRを読み直してください）" to Color(0xFFC62828)
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text("● $label", color = color, style = MaterialTheme.typography.bodySmall)
            if (connection != ConnectionState.NotPaired) {
                Text(
                    "送信済 $sentCount 件 / 待機 $pendingCount 件",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        if (connection != ConnectionState.NotPaired) {
            TextButton(onClick = onForget) { Text("接続解除") }
        }
    }
}

@Composable
private fun PairingDialog(state: PairingUiState, onConfirm: () -> Unit, onCancel: () -> Unit) {
    when (state) {
        PairingUiState.Idle -> Unit
        is PairingUiState.Confirm -> AlertDialog(
            onDismissRequest = onCancel,
            title = { Text("接続先を設定しますか？") },
            text = { Text("PC名: ${state.payload.name}\n接続候補: ${state.payload.urls.size} 件") },
            confirmButton = { TextButton(onClick = onConfirm) { Text("接続") } },
            dismissButton = { TextButton(onClick = onCancel) { Text("キャンセル") } },
        )
        is PairingUiState.Connecting -> AlertDialog(
            onDismissRequest = {},
            title = { Text("接続を確認しています...") },
            text = { Text("${state.payload.name} の接続候補 ${state.payload.urls.size} 件を確認中") },
            confirmButton = {},
        )
        is PairingUiState.Failed -> AlertDialog(
            onDismissRequest = onCancel,
            title = { Text("接続できませんでした") },
            text = {
                Text(
                    "${state.payload.name} のどの接続候補にも届きませんでした。\n" +
                        "USBテザリングが有効か、PC のファイアウォールを確認してください。",
                )
            },
            confirmButton = { TextButton(onClick = onConfirm) { Text("再試行") } },
            dismissButton = { TextButton(onClick = onCancel) { Text("閉じる") } },
        )
    }
}

/** ImageProxy を回転補正した JPEG にする。 */
private fun ImageProxy.toUprightJpeg(rotationDegrees: Int): ByteArray {
    val source = toBitmap()
    val upright = if (rotationDegrees == 0) {
        source
    } else {
        Bitmap.createBitmap(
            source, 0, 0, source.width, source.height,
            Matrix().apply { postRotate(rotationDegrees.toFloat()) },
            true,
        )
    }
    return ByteArrayOutputStream().use { out ->
        upright.compress(Bitmap.CompressFormat.JPEG, 80, out)
        out.toByteArray()
    }
}

@Composable
private fun CameraPreview(vm: ScannerViewModel) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val scanner: BarcodeScanner = remember {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS)
                .build()
        )
    }
    val controller = remember { LifecycleCameraController(context) }

    DisposableEffect(lifecycleOwner, controller, scanner) {
        val executor = Executors.newSingleThreadExecutor()
        controller.setEnabledUseCases(CameraController.IMAGE_ANALYSIS)
        controller.setImageAnalysisResolutionSelector(
            ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(
                    ResolutionStrategy(Size(1280, 960), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                )
                .build()
        )
        controller.setImageAnalysisAnalyzer(executor) { imageProxy ->
            val media = imageProxy.image
            if (media == null) {
                imageProxy.close()
                return@setImageAnalysisAnalyzer
            }
            val rotation = imageProxy.imageInfo.rotationDegrees
            val upright = rotation % 180 == 0
            val width = if (upright) imageProxy.width else imageProxy.height
            val height = if (upright) imageProxy.height else imageProxy.width

            scanner.process(InputImage.fromMediaImage(media, rotation))
                .addOnSuccessListener(executor) { barcodes ->
                    val added = vm.onBarcodes(barcodes, width, height)
                    if (added.isNotEmpty() && vm.isPaired) {
                        runCatching { imageProxy.toUprightJpeg(rotation) }
                            .onSuccess { vm.enqueueUpload(it, width, height, added) }
                    }
                }
                .addOnCompleteListener(executor) { imageProxy.close() }
        }
        controller.bindToLifecycle(lifecycleOwner)
        onDispose {
            controller.unbind()
            controller.clearImageAnalysisAnalyzer()
            executor.shutdown()
            scanner.close()
        }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            PreviewView(ctx).apply {
                this.controller = controller
                scaleType = PreviewView.ScaleType.FILL_CENTER
            }
        },
    )
}
