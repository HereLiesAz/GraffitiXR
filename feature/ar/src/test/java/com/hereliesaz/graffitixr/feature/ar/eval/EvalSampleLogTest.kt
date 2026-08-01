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
        relocBackboneFeatures = reloc?.backboneFeatures ?: EvalSampleLog.NOT_SAMPLED,
        relocBackboneMatches = reloc?.backboneMatches ?: EvalSampleLog.NOT_SAMPLED,
        relocBackboneInliers = reloc?.backboneInliers ?: EvalSampleLog.NOT_SAMPLED,
    )

    @Test
    fun `header lists all columns in order`() {
        assertEquals(
            "tsMs,deviceClass,marksVisible,errMm,errDeg,jitterMm,availability," +
                "voxelUpdateMs,voxelKeyframeMs,surfaceMeshMs,drawMs,pnpRelocMs,cpuPct,batteryMa,tempC," +
                "nativeHeapKb,relocReject,relocMatches,relocInliers,relocDetected," +
                "relocObliquityDeg,relocRectifiedCorr," +
                "relocBackboneFeatures,relocBackboneMatches,relocBackboneInliers," +
                "captureRotationNeededDeg,liveRotationNeededDeg",
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
                "-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1",
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
     * `EVALUATION.md` **E0b** compares relocalization between device orientations, so the
     * orientation has to be *in the data*. Without this column the experiment's independent
     * variable exists only in whoever ran it remembering which way they were holding the phone —
     * and `screenOrientation="fullUser"` means a single run can straddle both conditions.
     */
    @Test
    fun `rotationNeeded reaches its own column`() {
        val fields = EvalSampleLog.fields(sample().copy(captureRotationNeededDeg = 90))
        assertEquals("90", fields[EvalSampleLog.COLUMNS.indexOf("captureRotationNeededDeg")])
    }

    /**
     * The defect this pair of columns was split to fix, pinned as a test.
     *
     * `PlaneMarks.backProject` runs once per target inside `MetricFingerprintBuilder.build`, so
     * §8's frame mismatch is baked into the fingerprint's 3D points **at capture**. E0b's
     * independent variable is therefore the capture rotation, and the live rotation is a different
     * quantity that can legitimately differ from it on the very same row.
     *
     * The scenario below is the one a single column got wrong: captured in portrait (skewed
     * fingerprint), relocalized in landscape. Collapsing these two into one value files this row
     * under 0 — E0b's *control* condition — and a genuine effect averages toward nothing, which is
     * indistinguishable from the falsification E0b is supposed to be able to report.
     */
    @Test
    fun `a portrait capture relocalized in landscape keeps both rotations distinct`() {
        val fields = EvalSampleLog.fields(
            sample().copy(captureRotationNeededDeg = 90, liveRotationNeededDeg = 0),
        )
        val capture = fields[EvalSampleLog.COLUMNS.indexOf("captureRotationNeededDeg")]
        val live = fields[EvalSampleLog.COLUMNS.indexOf("liveRotationNeededDeg")]
        assertEquals("the fingerprint was built under the mismatch", "90", capture)
        assertEquals("the device is held square right now", "0", live)
        assertTrue(
            "grouping E0b by the live rotation would file this defect-bearing row as the control",
            capture != live,
        )
    }

    /**
     * A target restored from a saved project was never captured by this renderer, so its rotation
     * is genuinely unknown. It must read as unknown and not as landscape — `-1` excludes the row
     * from E0b's grouping, `0` would silently enrol it in the control arm.
     */
    @Test
    fun `an unobserved capture reads unknown, never as the landscape control`() {
        val fields = EvalSampleLog.fields(sample().copy(liveRotationNeededDeg = 0))
        assertEquals(
            "-1", fields[EvalSampleLog.COLUMNS.indexOf("captureRotationNeededDeg")],
        )
    }

    /**
     * The trap this column could most easily fall into. **0 is E0b's control condition** — landscape,
     * no frame mismatch, the orientation predicted to work — so it is the single most important
     * value the column ever carries. Had it doubled as the not-sampled sentinel, every successful
     * control row would have been indistinguishable from missing data, and the experiment would
     * have been unable to report its own negative result.
     */
    @Test
    fun `rotationNeeded zero is the landscape control, not a missing value`() {
        val landscape = EvalSampleLog.fields(sample().copy(captureRotationNeededDeg = 0))
        val unsampled = EvalSampleLog.fields(sample())
        val i = EvalSampleLog.COLUMNS.indexOf("captureRotationNeededDeg")
        assertEquals("0", landscape[i])
        assertEquals("-1", unsampled[i])
        assertTrue("the control condition must not read as absent", landscape[i] != unsampled[i])
    }

    /**
     * `IMPLEMENTATION.md` 2.11 — the backbone counts get their own columns, beside the totals.
     * Reusing `relocMatches` for the backbone count would have made the two shortfalls E5 needs to
     * tell apart the same number.
     */
    @Test
    fun `backbone counts reach their own columns, beside the totals`() {
        val d = RelocDiagnostics(
            RelocReject.OK, matches = 40, inliers = 24, detected = 1400,
            backboneFeatures = 300, backboneMatches = 31, backboneInliers = 19,
        )
        val fields = EvalSampleLog.fields(sample(reloc = d))
        assertEquals("300", fields[EvalSampleLog.COLUMNS.indexOf("relocBackboneFeatures")])
        assertEquals("31", fields[EvalSampleLog.COLUMNS.indexOf("relocBackboneMatches")])
        assertEquals("19", fields[EvalSampleLog.COLUMNS.indexOf("relocBackboneInliers")])
        assertEquals("40", fields[EvalSampleLog.COLUMNS.indexOf("relocMatches")])
        assertEquals("24", fields[EvalSampleLog.COLUMNS.indexOf("relocInliers")])
    }

    /**
     * The trap this trio could most easily fall into, and the one Phase 2 created.
     *
     * An empty `F_out` — the artwork covers the whole visible wall, so reloc has nothing to
     * bootstrap from — is a **real** measurement of zero, and it is the specific failure Phase 2's
     * risk section says the implementation must make legible. Had 0 doubled as the not-sampled
     * marker, every row recording that failure would have been indistinguishable from a row where
     * the reloc thread simply had not run, and the experiment could not report it.
     */
    @Test
    fun `an empty backbone logs as zero, distinct from not sampled`() {
        val wallFilling = RelocDiagnostics(
            RelocReject.FEW_MATCHES, matches = 2, inliers = 0, detected = 1400,
            backboneFeatures = 0, backboneMatches = 0, backboneInliers = 0,
        )
        val measured = EvalSampleLog.fields(sample(reloc = wallFilling))
        val unsampled = EvalSampleLog.fields(sample(reloc = null))
        for (col in listOf("relocBackboneFeatures", "relocBackboneMatches", "relocBackboneInliers")) {
            val i = EvalSampleLog.COLUMNS.indexOf(col)
            assertEquals("0", measured[i])
            assertEquals("-1", unsampled[i])
            assertTrue("$col: an empty backbone must not read as absent", measured[i] != unsampled[i])
        }
    }

    /**
     * A legacy fingerprint carries no partition and is therefore all backbone, so its count is the
     * whole point set. It must not arrive as 0 (which would say the opposite) nor as -1.
     */
    @Test
    fun `a legacy all-backbone fingerprint logs its full point count`() {
        val legacy = RelocDiagnostics(
            RelocReject.OK, matches = 40, inliers = 24, detected = 1400,
            backboneFeatures = 512, backboneMatches = 40, backboneInliers = 24,
        )
        val fields = EvalSampleLog.fields(sample(reloc = legacy))
        assertEquals("512", fields[EvalSampleLog.COLUMNS.indexOf("relocBackboneFeatures")])
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
