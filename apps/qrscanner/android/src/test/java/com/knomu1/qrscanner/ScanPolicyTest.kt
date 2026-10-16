package com.knomu1.qrscanner

import com.google.mlkit.vision.barcode.common.Barcode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanPolicyTest {
    private val strict = ScanSettings()

    @Test
    fun ean13_validChecksum_isAccepted() {
        assertNull(ValueValidator.check(Barcode.FORMAT_EAN_13, "4901234567894", strict))
    }

    @Test
    fun ean13_wrongChecksum_isRejected() {
        assertNotNull(ValueValidator.check(Barcode.FORMAT_EAN_13, "4901234567895", strict))
    }

    @Test
    fun ean8_andUpcA_checksums() {
        assertNull(ValueValidator.check(Barcode.FORMAT_EAN_8, "12345670", strict))
        assertNotNull(ValueValidator.check(Barcode.FORMAT_EAN_8, "12345671", strict))
        assertNull(ValueValidator.check(Barcode.FORMAT_UPC_A, "012345678905", strict))
        assertNotNull(ValueValidator.check(Barcode.FORMAT_UPC_A, "012345678906", strict))
    }

    @Test
    fun itf_requiresEvenLengthAndMinLength() {
        assertNull(ValueValidator.check(Barcode.FORMAT_ITF, "1234567890", strict))
        assertNotNull(ValueValidator.check(Barcode.FORMAT_ITF, "123456789", strict)) // 奇数桁
        assertNotNull(ValueValidator.check(Barcode.FORMAT_ITF, "1234", strict)) // 短すぎる部分読み
    }

    @Test
    fun code39_shortValue_isRejected() {
        assertNull(ValueValidator.check(Barcode.FORMAT_CODE_39, "CODE39-TEST", strict))
        assertNotNull(ValueValidator.check(Barcode.FORMAT_CODE_39, "AB1", strict))
    }

    @Test
    fun validationDisabled_acceptsEverything() {
        val off = strict.copy(validateValues = false)
        assertNull(ValueValidator.check(Barcode.FORMAT_EAN_13, "4901234567895", off))
    }

    // --- 一致確認 ---

    private fun frame(t: ConsensusTracker, vararg values: String, window: Int = 5, required: Int = 3) =
        t.onFrame(values.associateWith { true }, window, required) { false }

    @Test
    fun consensus_confirmsOnlyAfterRequiredHits() {
        val t = ConsensusTracker()
        assertTrue(frame(t, "A").confirmed.isEmpty())
        assertTrue(frame(t, "A").confirmed.isEmpty())
        assertEquals(setOf("A"), frame(t, "A").confirmed)
    }

    @Test
    fun consensus_toleratesFlicker() {
        val t = ConsensusTracker()
        frame(t, "A")
        frame(t) // 検出なし
        frame(t, "A")
        frame(t)
        assertEquals(setOf("A"), frame(t, "A").confirmed) // 5 フレーム中 3 回
    }

    @Test
    fun consensus_misreadsThatDiffer_neverConfirm_andAreCountedAsDiscarded() {
        val t = ConsensusTracker()
        var discarded = 0
        listOf("X1", "X2", "X3", "X4", "X5", "X6", "X7", "X8").forEach {
            val r = frame(t, it)
            assertTrue(r.confirmed.isEmpty())
            discarded += r.discarded
        }
        assertTrue(discarded >= 3)
    }

    @Test
    fun consensus_confirmedValueIsReportedOnce() {
        val t = ConsensusTracker()
        val confirmed = HashSet<String>()
        var count = 0
        repeat(10) {
            val r = t.onFrame(mapOf("A" to true), 5, 3) { it in confirmed }
            count += r.confirmed.size
            confirmed += r.confirmed
        }
        assertEquals(1, count)
    }

    @Test
    fun consensus_invalidValueNeverConfirms_andIsCountedAsInvalid() {
        val t = ConsensusTracker()
        var invalid = 0
        repeat(3) { assertTrue(t.onFrame(mapOf("BAD" to false), 5, 1) { false }.confirmed.isEmpty()) }
        repeat(6) { invalid += t.onFrame(emptyMap(), 5, 1) { false }.invalid }
        assertEquals(1, invalid)
    }

    @Test
    fun consensus_window1Required1_confirmsImmediately() {
        val t = ConsensusTracker()
        assertEquals(setOf("A"), frame(t, "A", window = 1, required = 1).confirmed)
    }
}

class FormatCatalogTest {
    @Test
    fun catalogCoversAllSupportedFormats_withoutDuplicates() {
        val formats = FORMAT_CATALOG.map { it.format }
        assertEquals(formats.size, formats.toSet().size)
        assertEquals(13, formats.size)
        assertTrue(ALWAYS_ON_FORMAT in formats)
    }

    @Test
    fun defaultSettings_enableEveryCatalogFormat() {
        assertEquals(FORMAT_CATALOG.map { it.format }.toSet(), ScanSettings().enabledFormats)
    }
}
