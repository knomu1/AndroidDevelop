package com.knomu1.qrscanner

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter

private data class SampleBarcode(val label: String, val format: BarcodeFormat, val value: String)

private val sampleBarcodes = listOf(
    SampleBarcode("JAN-13 サンプル1", BarcodeFormat.EAN_13, "4901234567894"),
    SampleBarcode("JAN-13 サンプル2", BarcodeFormat.EAN_13, "4512345678906"),
    SampleBarcode("JAN-13 サンプル3", BarcodeFormat.EAN_13, "4000000000006"),
    SampleBarcode("JAN-8 サンプル", BarcodeFormat.EAN_8, "12345670"),
)

@Composable
fun SampleBarcodeDialog(onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.85f),
            shape = MaterialTheme.shapes.large,
        ) {
            LazyColumn(modifier = Modifier.padding(16.dp)) {
                items(sampleBarcodes) { sample ->
                    SampleBarcodeItem(sample)
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun SampleBarcodeItem(sample: SampleBarcode) {
    val matrix = remember(sample) {
        MultiFormatWriter().encode(sample.value, sample.format, 0, BAR_HEIGHT_PX)
    }
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Text(sample.label, style = MaterialTheme.typography.titleSmall)
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .background(Color.White),
        ) {
            val moduleWidth = size.width / matrix.width
            for (x in 0 until matrix.width) {
                if (matrix.get(x, BAR_HEIGHT_PX / 2)) {
                    drawRect(
                        color = Color.Black,
                        topLeft = Offset(x * moduleWidth, 0f),
                        size = Size(moduleWidth, size.height),
                    )
                }
            }
        }
        Text(sample.value, style = MaterialTheme.typography.bodyLarge)
    }
}

private const val BAR_HEIGHT_PX = 100
