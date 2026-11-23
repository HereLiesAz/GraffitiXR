package com.hereliesaz.graffitixr.feature.dashboard

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import java.nio.FloatBuffer

/**
 * Developer measurement (Settings > Developer): how long one SuperPoint forward pass over a 640 x 480
 * image takes on this phone, for the three model files (the shipped fp32, an fp16 conversion, an int8
 * QDQ quantization) and three ONNX Runtime execution providers (CPU, XNNPACK, NNAPI).
 *
 * The app's own SuperPoint runs through OpenCV DNN (`SuperPointDetector.cpp`); this does not touch it. It
 * only says whether another runtime or a quantized model is worth wiring in, so the numbers are to be
 * compared with the existing SuperPoint timings, not trusted as a tracker result. The input is a fixed
 * pseudo-random image (a convolutional net's speed does not depend on its content). Each line goes to
 * [onLine] and to logcat under `SPBench`; a provider or model the device rejects reports the error and the
 * run carries on.
 */
internal object SuperPointBench {
    private const val TAG = "SPBench"
    private const val W = 640
    private const val H = 480
    private const val WARMUP = 3
    private const val RUNS = 12
    private const val THREADS = 4

    private val models = listOf(
        "fp32" to "superpoint.onnx", // the shipped model, merged in from core:nativebridge's assets
        "fp16" to "superpoint_bench/superpoint.fp16.onnx",
        "int8" to "superpoint_bench/superpoint.int8.onnx",
    )

    private val providers = listOf("CPU", "XNNPACK", "NNAPI")

    fun run(context: Context, onLine: (String) -> Unit) {
        fun say(s: String) { Log.i(TAG, s); onLine(s) }
        val env = OrtEnvironment.getEnvironment()
        val input = FloatArray(W * H) { ((it * 2654435761L) ushr 7 and 0xFF).toInt() / 255f }
        say("ONNX Runtime, ${W}x$H, median of $RUNS after $WARMUP warm-up, $THREADS threads (ms)")
        for ((name, asset) in models) {
            val bytes = try {
                context.assets.open(asset).use { it.readBytes() }
            } catch (e: Exception) {
                say("$name: missing asset $asset (${e.javaClass.simpleName})"); continue
            }
            for (provider in providers) {
                try {
                    OrtSession.SessionOptions().use { opts ->
                        opts.setIntraOpNumThreads(THREADS)
                        when (provider) {
                            "XNNPACK" -> opts.addXnnpack(mapOf("intra_op_num_threads" to THREADS.toString()))
                            "NNAPI" -> opts.addNnapi()
                        }
                        env.createSession(bytes, opts).use { session ->
                            OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, 1, H.toLong(), W.toLong())).use { tensor ->
                                repeat(WARMUP) { session.run(mapOf("image" to tensor)).close() }
                                val ms = DoubleArray(RUNS) {
                                    val t = System.nanoTime()
                                    session.run(mapOf("image" to tensor)).close()
                                    (System.nanoTime() - t) / 1e6
                                }.sorted()
                                say("%-5s %-8s %7.1f  (min %.1f)".format(name, provider, ms[RUNS / 2], ms[0]))
                            }
                        }
                    }
                } catch (e: Throwable) {
                    say("$name $provider: ${e.javaClass.simpleName}: ${e.message?.take(80)}")
                }
            }
        }
        say("done")
    }
}
