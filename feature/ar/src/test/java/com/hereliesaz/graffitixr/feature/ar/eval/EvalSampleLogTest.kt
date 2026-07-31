package com.hereliesaz.graffitixr.feature.ar.eval

import com.hereliesaz.graffitixr.common.model.RelocDiagnostics
import com.hereliesaz.graffitixr.common.model.RelocReject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * IMPLEMENTATION.md todo **6a.2** — the CSV shape guard, plus the original exact-output checks.
 *
 * Column drift is the class of bug that silently corrupts every downstream analysis rather than
 * failing: add a field to the row and forget the header (or the reverse) and every column past the
 * insertion point shifts, in a file that still parses cleanly. Nothing errors — the numbers are just
 * wrong, and the mistake surfaces weeks later while interpreting results, if at all.
 *
 * Header and row now derive from one [EvalSampleLog.COLUMNS] list, so the drift is structurally
 * hard; these make it impossible to land unnoticed anyway.
 */
class EvalSampleLogTest {

    private fun sample(deviceClass: String = "mono", reloc: RelocDiagnostics? = null) = EvalSample(
        tsMs = 1_700_000_000_000L,
        deviceClass = deviceClass,
        marksVisible = true,
        errMm = 4.25f, errDeg = 0.5f, jitterMm = 1.5f, availability = 0.98f,
        stageMs = floatArrayOf(1f, 2f, 3f, 4f, 5f),
        cpuPct = 42f, batteryMa = -350f, tempC = 31.5f, nativeHeapKb = 123_456L,
        relocReject = reloc?.reject?.ordinal ?: EvalSampleLog.NOT_SAMPLED,
        relocMatches = reloc?.matches ?: EvalSampleLog.NOT_SAMPLED,
        relocInliers = reloc?.inliers ?: EvalSampleLog.NOT_SAMPLED,
        relocDetected = reloc?.detected ?: EvalSampleLog.NOT_SAMPLED,
        relocObliquityDeg = reloc?.obliquityDeg ?: EvalSampleLog.NOT_SAMPLED,
        relocRectifiedCorr = reloc?.rectifiedCorrespondences ?: EvalSampleLog.NOT_SAMPLED,
    )

    @Test
    fun `header lists all columns in order`() {
        assertEquals(
            "tsMs,deviceClass,marksVisible,errMm,errDeg,jitterMm,availability," +
                "voxelUpdateMs,voxelKeyframeMs,surfaceMeshMs,drawMs,pnpRelocMs,cpuPct,batteryMa,tempC," +
                "nativeHeapKb,relocReject,relocMatches,relocInliers,relocDetected," +
                "relocObliquityDeg,relocRectifiedCorr",
            EvalSampleLog.CSV_HEADER,
        )
    }

    @Test
    fun `row serializes fields in header order`() {
        val row = EvalSample(
            tsMs = 12L, deviceClass = "dual", marksVisible = true,
            errMm = 1.5f, errDeg = 0.25f, jitterMm = 3f, availability = 1f,
            stageMs = floatArrayOf(2f, 0f, 4f, 1f, 8f), cpuPct = 30f, batteryMa = -450f, tempC = 31f,
            nativeHeapKb = 20480L,
        )
        assertEquals(
            "12,dual,true,1.5,0.25,3.0,1.0,2.0,0.0,4.0,1.0,8.0,30.0,-450.0,31.0,20480," +
                "-1,-1,-1,-1,-1,-1",
            EvalSampleLog.toCsvRow(row),
        )
    }

    @Test
    fun `header column count equals emitted field count`() {
        assertEquals(
            "header and row must stay in lockstep",
            EvalSampleLog.COLUMNS.size,
            EvalSampleLog.fields(sample()).size,
        )
        assertEquals(
            EvalSampleLog.CSV_HEADER.split(",").size,
            EvalSampleLog.toCsvRow(sample()).split(",").size,
        )
    }

    @Test
    fun `header is derived from the column list, not restated`() {
        assertEquals(EvalSampleLog.COLUMNS.joinToString(","), EvalSampleLog.CSV_HEADER)
    }

    /** A duplicated name makes two quantities indistinguishable to any consumer selecting by name. */
    @Test
    fun `column names are unique`() {
        assertEquals(EvalSampleLog.COLUMNS.size, EvalSampleLog.COLUMNS.toSet().size)
    }

    /**
     * A short stageMs array must not shorten the row. The contract is five stages; a caller that
     * supplies fewer should pad, not shift every following column left.
     */
    @Test
    fun `a short stage array still emits five stage columns`() {
        val short = sample().copy(stageMs = floatArrayOf(1f, 2f))
        assertEquals(EvalSampleLog.COLUMNS.size, EvalSampleLog.fields(short).size)
    }

    /** …and an over-long one must not lengthen it. */
    @Test
    fun `an over-long stage array still emits five stage columns`() {
        val long = sample().copy(stageMs = FloatArray(9) { it.toFloat() })
        assertEquals(EvalSampleLog.COLUMNS.size, EvalSampleLog.fields(long).size)
    }

    /**
     * `deviceClass` reaches the log as a free-form string from a caller. One comma in it shifts every
     * subsequent column of that row — silently, because the file still parses.
     *
     * Asserted by PARSING the row, not by looking for a quote. An earlier version of this test only
     * checked that `"mono,rear"` appeared somewhere in the output, under a name promising the
     * columns could not shift — which it never checked. Note a naive `split(",")` on this row gives
     * one token too many; that is the bug, and it is why the reader below is quote-aware.
     */
    @Test
    fun `a comma in a free-form field cannot shift the columns`() {
        val row = EvalSampleLog.toCsvRow(sample(deviceClass = "mono,rear"))
        assertTrue("the value must be quoted, got: $row", row.contains("\"mono,rear\""))

        val parsed = parseCsvRow(row)
        assertEquals("column count must survive an embedded comma", EvalSampleLog.COLUMNS.size, parsed.size)
        assertEquals("mono,rear", parsed[EvalSampleLog.COLUMNS.indexOf("deviceClass")])
        // And the column AFTER it must still be itself, which is what "shift" would break.
        assertEquals("true", parsed[EvalSampleLog.COLUMNS.indexOf("marksVisible")])

        // The naive reader really does miscount — recorded so the quoting is not mistaken for
        // cosmetic, and so a future consumer written with split(",") is a known-bad choice.
        assertEquals(EvalSampleLog.COLUMNS.size + 1, row.split(",").size)
    }

    /** Minimal RFC4180 field splitter: quote-aware, handles doubled quotes inside a quoted field. */
    private fun parseCsvRow(row: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < row.length) {
            val c = row[i]
            when {
                inQuotes && c == '"' && i + 1 < row.length && row[i + 1] == '"' -> { cur.append('"'); i++ }
                c == '"' -> inQuotes = !inQuotes
                c == ',' && !inQuotes -> { out.add(cur.toString()); cur.setLength(0) }
                else -> cur.append(c)
            }
            i++
        }
        out.add(cur.toString())
        return out
    }

    @Test
    fun `a quote in a free-form field is doubled`() {
        assertEquals("\"a\"\"b\"", EvalSampleLog.csvEscape("a\"b"))
    }

    @Test
    fun `plain values are not quoted`() {
        assertEquals("mono", EvalSampleLog.csvEscape("mono"))
    }

    /**
     * Not-sampled is -1, not 0. Zero matches and zero detected features are both real measurements —
     * "the detector found nothing" is exactly the diagnosis the reject histogram exists to surface —
     * so zero cannot double as the marker for "this row carries no reloc data".
     */
    @Test
    fun `unsampled reloc diagnostics are minus one, not zero`() {
        val fields = EvalSampleLog.fields(sample(reloc = null))
        assertEquals("-1", fields[EvalSampleLog.COLUMNS.indexOf("relocReject")])
        assertEquals("-1", fields[EvalSampleLog.COLUMNS.indexOf("relocMatches")])
        assertEquals("-1", fields[EvalSampleLog.COLUMNS.indexOf("relocInliers")])
        assertEquals("-1", fields[EvalSampleLog.COLUMNS.indexOf("relocDetected")])
    }

    @Test
    fun `sampled reloc diagnostics land in their named columns`() {
        val d = RelocDiagnostics(RelocReject.FEW_INLIERS, matches = 40, inliers = 7, detected = 1200)
        val fields = EvalSampleLog.fields(sample(reloc = d))
        assertEquals(
            RelocReject.FEW_INLIERS.ordinal.toString(),
            fields[EvalSampleLog.COLUMNS.indexOf("relocReject")],
        )
        assertEquals("40", fields[EvalSampleLog.COLUMNS.indexOf("relocMatches")])
        assertEquals("7", fields[EvalSampleLog.COLUMNS.indexOf("relocInliers")])
        assertEquals("1200", fields[EvalSampleLog.COLUMNS.indexOf("relocDetected")])
    }

    @Test
    fun `obliquity and rectified correspondences reach their own columns`() {
        val d = RelocDiagnostics(
            RelocReject.OK, matches = 40, inliers = 30, detected = 900,
            obliquityDeg = 37, rectifiedCorrespondences = 12,
        )
        val fields = EvalSampleLog.fields(sample(reloc = d))
        assertEquals("37", fields[EvalSampleLog.COLUMNS.indexOf("relocObliquityDeg")])
        assertEquals("12", fields[EvalSampleLog.COLUMNS.indexOf("relocRectifiedCorr")])
    }

    /**
     * A zero-detected reading must log as 0 and stay distinguishable from not-sampled. This is the
     * exact pair the sentinel choice exists to separate.
     */
    @Test
    fun `zero detected is logged as zero, distinct from not sampled`() {
        val d = RelocDiagnostics(RelocReject.NO_FEATURES, matches = 0, inliers = 0, detected = 0)
        val fields = EvalSampleLog.fields(sample(reloc = d))
        assertEquals("0", fields[EvalSampleLog.COLUMNS.indexOf("relocDetected")])
        assertEquals("0", fields[EvalSampleLog.COLUMNS.indexOf("relocMatches")])
    }
}
