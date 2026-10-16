package com.knomu1.qrscanner

import com.google.mlkit.vision.barcode.common.Barcode

enum class MisreadRisk(val label: String) {
    Low("低"),
    Medium("中"),
    High("高"),
}

/** このアプリ（ML Kit）が読み取りに対応しているバーコード形式の説明。 */
data class FormatInfo(
    val format: Int,
    val name: String,
    val kind: String,
    val errorDetection: String,
    val risk: MisreadRisk,
    val usage: String,
)

val FORMAT_CATALOG: List<FormatInfo> = listOf(
    FormatInfo(
        Barcode.FORMAT_QR_CODE, "QR コード", "2次元",
        "リード・ソロモン誤り訂正", MisreadRisk.Low, "URL・汎用データ。ペアリングにも使用",
    ),
    FormatInfo(
        Barcode.FORMAT_DATA_MATRIX, "DataMatrix", "2次元",
        "リード・ソロモン誤り訂正", MisreadRisk.Low, "小型部品・医療機器・電子部品",
    ),
    FormatInfo(
        Barcode.FORMAT_AZTEC, "Aztec", "2次元",
        "リード・ソロモン誤り訂正", MisreadRisk.Low, "搭乗券・チケット",
    ),
    FormatInfo(
        Barcode.FORMAT_PDF417, "PDF417", "2次元（スタック型）",
        "リード・ソロモン誤り訂正", MisreadRisk.Low, "免許証・配送ラベル",
    ),
    FormatInfo(
        Barcode.FORMAT_EAN_13, "JAN-13 (EAN-13)", "1次元",
        "チェックデジット 1 桁", MisreadRisk.Medium, "一般商品（13 桁）",
    ),
    FormatInfo(
        Barcode.FORMAT_EAN_8, "JAN-8 (EAN-8)", "1次元",
        "チェックデジット 1 桁", MisreadRisk.Medium, "小型商品（8 桁）",
    ),
    FormatInfo(
        Barcode.FORMAT_UPC_A, "UPC-A", "1次元",
        "チェックデジット 1 桁", MisreadRisk.Medium, "北米の商品（12 桁）",
    ),
    FormatInfo(
        Barcode.FORMAT_UPC_E, "UPC-E", "1次元",
        "チェックデジット 1 桁（UPC-A の短縮形）", MisreadRisk.Medium, "北米の小型商品",
    ),
    FormatInfo(
        Barcode.FORMAT_CODE_128, "CODE128", "1次元",
        "チェックサム（mod 103）", MisreadRisk.Medium, "物流・宅配伝票・社内ラベル",
    ),
    FormatInfo(
        Barcode.FORMAT_CODE_93, "CODE93", "1次元",
        "チェック文字 2 つ", MisreadRisk.Medium, "物流・郵便（CODE39 の高密度版）",
    ),
    FormatInfo(
        Barcode.FORMAT_CODE_39, "CODE39", "1次元",
        "なし（チェック文字は任意）", MisreadRisk.High, "工業・ID カード・車両",
    ),
    FormatInfo(
        Barcode.FORMAT_ITF, "ITF (Interleaved 2 of 5)", "1次元",
        "なし（偶数桁のみ。部分読みで誤読しやすい）", MisreadRisk.High, "物流ケース・段ボール",
    ),
    FormatInfo(
        Barcode.FORMAT_CODABAR, "CODABAR (NW-7)", "1次元",
        "なし", MisreadRisk.High, "宅配・図書館・血液バンク",
    ),
)
