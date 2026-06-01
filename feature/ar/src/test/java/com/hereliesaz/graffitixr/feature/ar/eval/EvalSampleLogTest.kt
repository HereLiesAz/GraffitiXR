package com.hereliesaz.graffitixr.feature.ar.eval

import org.junit.Assert.assertEquals
import org.junit.Test

class EvalSampleLogTest {
    @Test
    fun `header lists all columns in order`() {
        assertEquals(
            "tsMs,deviceClass,marksVisible,errMm,errDeg,jitterMm,availability," +
                "voxelUpdateMs,voxelKeyframeMs,surfaceMeshMs,drawMs,pnpRelocMs,cpuPct,batteryMa,tempC,nativeHeapKb",
            EvalSampleLog.CSV_HEADER
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
            "12,dual,true,1.5,0.25,3.0,1.0,2.0,0.0,4.0,1.0,8.0,30.0,-450.0,31.0,20480",
            EvalSampleLog.toCsvRow(row)
        )
    }
}
