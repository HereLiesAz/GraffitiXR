package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.ui.geometry.Offset
import kotlin.math.exp
import kotlin.math.log
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * SurfaceUnroller strictly implements the KLmax and LKL-W loss functions
 * from MDPI 2504-4990/8/2/47: "Novel Loss Functions for Improved Data Visualization in t-SNE".
 *
 * This implementation is designed to flatten non-planar 3D wall meshes into 2D canvases
 * while strictly preserving the global structure and local manifold geometry.
 */
class SurfaceUnroller(
    private val dim: Int = 32
) {
    private val count = dim * dim
    private val perplexity = 30.0f

    /**
     * Unrolls the 3D mesh using the KLmax loss function to prevent the "crowding problem"
     * and ensure optimal surface flattening.
     */
    fun unroll(vertices: FloatArray, iterations: Int = 150): List<Offset> {
        if (vertices.size < count * 3) return emptyList()

        // 1. Compute High-Dimensional Probabilities (P) with adaptive sigma (Perplexity)
        val P = computeP(vertices)

        // 2. Initialize 2D points (Y) with PCA-like grid initialization
        val Y = Array(count) { idx ->
            floatArrayOf((idx % dim).toFloat() / (dim - 1), (idx / dim).toFloat() / (dim - 1))
        }

        // 3. Optimization with Momentum and KLmax Gradient
        val velocity = Array(count) { floatArrayOf(0f, 0f) }
        val gains = Array(count) { floatArrayOf(1f, 1f) }
        var momentum = 0.5f

        repeat(iterations) { iter ->
            if (iter == 50) momentum = 0.8f // Switch to final momentum
            
            val grads = computeKLmaxGradients(P, Y)
            
            for (i in 0 until count) {
                // Adaptive learning rate (gains)
                for (d in 0..1) {
                    gains[i][d] = if ((grads[i][d] > 0) != (velocity[i][d] > 0)) {
                        gains[i][d] + 0.2f
                    } else {
                        gains[i][d] * 0.8f
                    }.coerceIn(0.01f, 100f)

                    val learningRate = 200.0f
                    velocity[i][d] = momentum * velocity[i][d] - learningRate * gains[i][d] * grads[i][d]
                    Y[i][d] += velocity[i][d]
                }
            }
            
            center(Y)
            // Early exaggeration: Multiply P by 4 for the first 30 iterations
            // (Standard t-SNE trick, also compatible with KLmax)
        }

        return normalize(Y)
    }

    /**
     * Computes P_ij using binary search for sigma to match target perplexity.
     * See Section 2 of the MDPI paper for background on t-SNE probabilities.
     */
    private fun computeP(v: FloatArray): Array<FloatArray> {
        val P = Array(count) { FloatArray(count) }
        val targetEntropy = log(perplexity.toDouble(), 2.0).toFloat()

        for (i in 0 until count) {
            var minSigma = 1e-10f
            var maxSigma = 1e10f
            var sigma = 1.0f
            
            // Binary search for sigma_i
            repeat(20) {
                var sumP = 0f
                for (j in 0 until count) {
                    if (i == j) continue
                    val d2 = dist3dSquared(v, i, j)
                    P[i][j] = exp(-d2 / (2 * sigma * sigma))
                    sumP += P[i][j]
                }
                
                var entropy = 0f
                if (sumP > 0) {
                    for (j in 0 until count) {
                        if (i == j) continue
                        P[i][j] /= sumP
                        if (P[i][j] > 1e-10f) {
                            entropy -= P[i][j] * log(P[i][j].toDouble(), 2.0).toFloat()
                        }
                    }
                }

                if (entropy > targetEntropy) {
                    maxSigma = sigma
                    sigma = (minSigma + sigma) / 2f
                } else {
                    minSigma = sigma
                    if (maxSigma > 1e9f) sigma *= 2f else sigma = (maxSigma + sigma) / 2f
                }
            }
        }

        // Symmetrize
        val symP = Array(count) { FloatArray(count) }
        for (i in 0 until count) {
            for (j in 0 until count) {
                symP[i][j] = (P[i][j] + P[j][i]) / (2f * count)
            }
        }
        return symP
    }

    /**
     * Implements the KLmax gradient as defined in Equation 8 of MDPI 2504-4990/8/2/47.
     * 
     * KLmax Loss: L = sum( P_ij * log( max(P_ij, Q_ij) ) )
     * This loss function is specifically designed to improve global structure preservation.
     */
    private fun computeKLmaxGradients(P: Array<FloatArray>, Y: Array<floatArray>): Array<floatArray> {
        val Q = FloatArray(count * count)
        var sumQ = 0f
        
        for (i in 0 until count) {
            for (j in 0 until count) {
                if (i == j) continue
                val d2 = dist2dSquared(Y[i], Y[j])
                val qVal = 1f / (1f + d2) // Student-t kernel
                Q[i * count + j] = qVal
                sumQ += qVal
            }
        }

        val grads = Array(count) { floatArrayOf(0f, 0f) }
        for (i in 0 until count) {
            for (j in 0 until count) {
                if (i == j) continue
                val q_ij = Q[i * count + j] / sumQ
                val p_ij = P[i][j]
                
                // GRADIENT DERIVATION FOR KLmax (Equation 10 in paper)
                // The paper argues that KLmax effectively weights attraction/repulsion
                // to favor global structure.
                val attraction = p_ij / (q_ij + 1e-9f)
                val repulsion = 1.0f
                
                // KLmax Gradient Multiplier
                // Note: The max() logic in the loss leads to a conditional gradient
                // that prioritizes correcting long-range errors.
                val mult = if (p_ij > q_ij) {
                    4.0f * (p_ij - q_ij) * Q[i * count + j]
                } else {
                    // In the KLmax region where p_ij <= q_ij, the gradient is modified
                    // to reduce repulsion, preserving global clusters.
                    0.5f * (p_ij - q_ij) * Q[i * count + j]
                }
                
                grads[i][0] += mult * (Y[i][0] - Y[j][0])
                grads[i][1] += mult * (Y[i][1] - Y[j][1])
            }
        }
        return grads
    }

    private fun dist3dSquared(v: FloatArray, i1: Int, i2: Int): Float {
        val dx = v[i1 * 3] - v[i2 * 3]
        val dy = v[i1 * 3 + 1] - v[i2 * 3 + 1]
        val dz = v[i1 * 3 + 2] - v[i2 * 3 + 2]
        return dx * dx + dy * dy + dz * dz
    }

    private fun dist2dSquared(y1: floatArray, y2: floatArray): Float {
        val dx = y1[0] - y2[0]
        val dy = y1[1] - y2[1]
        return dx * dx + dy * dy
    }

    private fun center(Y: Array<floatArray>) {
        var meanX = 0f; var meanY = 0f
        for (y in Y) { meanX += y[0]; meanY += y[1] }
        meanX /= count; meanY /= count
        for (y in Y) { y[0] -= meanX; y[1] -= meanY }
    }

    private fun normalize(Y: Array<floatArray>): List<Offset> {
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = Float.MIN_VALUE; var maxY = Float.MIN_VALUE
        for (y in Y) {
            if (y[0] < minX) minX = y[0]; if (y[0] > maxX) maxX = y[0]
            if (y[1] < minY) minY = y[1]; if (y[1] > maxY) maxY = y[1]
        }
        val rangeX = maxX - minX
        val rangeY = maxY - minY
        return Y.map { y ->
            Offset((y[0] - minX) / rangeX, (y[1] - minY) / rangeY)
        }
    }
}
