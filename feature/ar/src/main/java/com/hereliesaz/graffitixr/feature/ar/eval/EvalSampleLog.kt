package com.hereliesaz.graffitixr.feature.ar.eval

/** One throttled measurement tick. stageMs is indexed by the stage contract: 0=voxelUpdate,
 *  1=voxelKeyframe, 2=surfaceMesh, 3=draw, 4=pnpReloc. */
data class EvalSample(
    val tsMs: Long,
    val deviceClass: String,   // "dual" or "mono"
    val marksVisible: Boolean,
    val errMm: Float,          // pose error vs mark-PnP truth; -1 when marks not visible
    val errDeg: Float,
    val jitterMm: Float,
    val availability: Float,
    val stageMs: FloatArray,   // length 5
    val cpuPct: Float,
    val batteryMa: Float,      // BatteryManager CURRENT_NOW (µA→mA); negative = discharging
    val tempC: Float,
    val nativeHeapKb: Long,    // Debug.getNativeHeapAllocatedSize()/1024 — memory cost proxy
)

object EvalSampleLog {
    const val CSV_HEADER =
        "tsMs,deviceClass,marksVisible,errMm,errDeg,jitterMm,availability," +
            "voxelUpdateMs,voxelKeyframeMs,surfaceMeshMs,drawMs,pnpRelocMs,cpuPct,batteryMa,tempC,nativeHeapKb"

    fun toCsvRow(s: EvalSample): String {
        val st = FloatArray(5).also { System.arraycopy(s.stageMs, 0, it, 0, minOf(5, s.stageMs.size)) }
        return listOf(
            s.tsMs.toString(), s.deviceClass, s.marksVisible.toString(),
            s.errMm.toString(), s.errDeg.toString(), s.jitterMm.toString(), s.availability.toString(),
            st[0].toString(), st[1].toString(), st[2].toString(), st[3].toString(), st[4].toString(),
            s.cpuPct.toString(), s.batteryMa.toString(), s.tempC.toString(), s.nativeHeapKb.toString(),
        ).joinToString(",")
    }
}
