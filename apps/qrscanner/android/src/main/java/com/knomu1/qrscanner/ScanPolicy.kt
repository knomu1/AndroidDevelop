package com.knomu1.qrscanner

import android.content.Context
import com.google.mlkit.vision.barcode.common.Barcode

/** 設定で ON/OFF できる読み取り対象の形式（一覧は FormatCatalog.kt）。 */
val SUPPORTED_FORMATS: List<Int> = FORMAT_CATALOG.map { it.format }

/** ペアリング用 QR を読むために常に有効にする形式。 */
const val ALWAYS_ON_FORMAT = Barcode.FORMAT_QR_CODE

/**
 * 読み取りの厳格さの設定。
 * - 形式の絞り込み（ML Kit のスキャナー自体を絞る。全形式有効だと別形式への誤読が増える）
 * - 値の検証（チェックデジット・桁数）
 * - 複数フレームでの一致確認（直近 windowFrames フレーム中 requiredFrames 回以上で確定）
 */
data class ScanSettings(
    val enabledFormats: Set<Int> = SUPPORTED_FORMATS.toSet(),
    val windowFrames: Int = 5,
    val requiredFrames: Int = 3,
    val validateValues: Boolean = true,
    val minLength: Int = 6,
) {
    val effectiveRequired: Int get() = requiredFrames.coerceIn(1, windowFrames)
}

class ScanSettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("scan_policy", Context.MODE_PRIVATE)

    fun load(): ScanSettings {
        val defaults = ScanSettings()
        val formats = prefs.getString("formats", null)
            ?.split(',')?.mapNotNull { it.toIntOrNull() }?.toSet()
            ?.intersect(SUPPORTED_FORMATS.toSet())
            ?.takeIf { it.isNotEmpty() }
            ?: defaults.enabledFormats
        return ScanSettings(
            enabledFormats = formats + ALWAYS_ON_FORMAT,
            windowFrames = prefs.getInt("window", defaults.windowFrames).coerceIn(1, 15),
            requiredFrames = prefs.getInt("required", defaults.requiredFrames).coerceIn(1, 15),
            validateValues = prefs.getBoolean("validate", defaults.validateValues),
            minLength = prefs.getInt("minLength", defaults.minLength).coerceIn(1, 30),
        )
    }

    fun save(s: ScanSettings) {
        prefs.edit()
            .putString("formats", s.enabledFormats.joinToString(","))
            .putInt("window", s.windowFrames)
            .putInt("required", s.requiredFrames)
            .putBoolean("validate", s.validateValues)
            .putInt("minLength", s.minLength)
            .apply()
    }
}

/** 値そのものの妥当性検証。 */
object ValueValidator {
    /** 問題があれば理由、なければ null。 */
    fun check(format: Int, value: String, s: ScanSettings): String? {
        if (!s.validateValues) return null
        return when (format) {
            Barcode.FORMAT_EAN_13 -> gs1(value, 13)
            Barcode.FORMAT_EAN_8 -> gs1(value, 8)
            Barcode.FORMAT_UPC_A -> gs1(value, 12)
            Barcode.FORMAT_ITF -> when {
                !value.all(Char::isDigit) -> "数字以外を含む"
                value.length % 2 != 0 -> "桁数が奇数"
                value.length < s.minLength -> "桁数不足(${value.length}<${s.minLength})"
                else -> null
            }
            // チェックサムを持たない（または弱い）形式は、短すぎる読み取りを部分読みとして除外する
            Barcode.FORMAT_CODE_39, Barcode.FORMAT_CODABAR, Barcode.FORMAT_CODE_93 ->
                if (value.length < s.minLength) "桁数不足(${value.length}<${s.minLength})" else null
            else -> null
        }
    }

    private fun gs1(value: String, length: Int): String? {
        if (value.length != length || !value.all(Char::isDigit)) return "桁数/文字種が不正"
        val body = value.dropLast(1)
        val sum = body.reversed().mapIndexed { i, c -> (c - '0') * if (i % 2 == 0) 3 else 1 }.sum()
        val check = (10 - sum % 10) % 10
        return if (check == value.last() - '0') null else "チェックデジット不一致"
    }
}

data class ScanStats(
    val confirmed: Int = 0,
    /** 一度は読めたが、多数決の条件に届かないまま消えた候補（ノイズ・誤読を抑えた数）。 */
    val discardedByVote: Int = 0,
    /** 値の検証（チェックデジット・桁数）で除外した候補。 */
    val rejectedInvalid: Int = 0,
)

/**
 * 複数フレームでの一致確認。
 * 同じ値が直近 window フレーム中 required 回以上、検出されたら「確定」とする。
 * フレームごとに必ず呼ぶこと（検出ゼロのフレームも窓を進めるため）。
 */
class ConsensusTracker {
    private class Candidate(val valid: Boolean, var confirmed: Boolean) {
        val seen = ArrayDeque<Long>()
    }

    data class Result(val confirmed: Set<String>, val discarded: Int, val invalid: Int)

    private val candidates = HashMap<String, Candidate>()
    private var frameIndex = 0L

    @Synchronized
    fun onFrame(
        entries: Map<String, Boolean>,
        window: Int,
        required: Int,
        alreadyConfirmed: (String) -> Boolean,
    ): Result {
        frameIndex++
        val confirmedNow = HashSet<String>()

        for ((value, valid) in entries) {
            val c = candidates.getOrPut(value) { Candidate(valid, alreadyConfirmed(value)) }
            c.seen.addLast(frameIndex)
        }

        val minIndex = frameIndex - window + 1
        var discarded = 0
        var invalid = 0
        val it = candidates.entries.iterator()
        while (it.hasNext()) {
            val (value, c) = it.next()
            while (c.seen.isNotEmpty() && c.seen.first() < minIndex) c.seen.removeFirst()

            if (c.seen.isEmpty()) {
                it.remove()
                if (!c.confirmed) if (c.valid) discarded++ else invalid++
                continue
            }
            // 確定判定は「このフレームで検出された値」に限る（画像と値を同じフレームで送るため）
            if (value in entries && c.valid && !c.confirmed && c.seen.size >= required) {
                c.confirmed = true
                confirmedNow += value
            }
        }
        return Result(confirmedNow, discarded, invalid)
    }

    @Synchronized
    fun reset() {
        candidates.clear()
    }
}
