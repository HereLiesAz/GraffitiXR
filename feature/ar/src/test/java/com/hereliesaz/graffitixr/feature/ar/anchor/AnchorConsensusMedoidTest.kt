package com.hereliesaz.graffitixr.feature.ar.anchor

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AnchorConsensusMedoidTest {

    @Test fun `medoid is a real vote when independent axis medians would invent a phantom point`() {
        val votes = listOf(
            floatArrayOf(0f, 2f, 5f),
            floatArrayOf(5f, 5f, 3f),
            floatArrayOf(2f, 1f, 2f),
        )

        // The old per-axis lower median is (2, 2, 3), assembled from three different votes.
        val phantomAxisMedian = floatArrayOf(2f, 2f, 3f)
        assertFalse(votes.any { it.contentEquals(phantomAxisMedian) })

        // The true medoid for these votes is the third point: it has the smallest total distance to
        // the other two and, critically, is a position an anchor actually reported.
        assertArrayEquals(votes[2], medoidPosition(votes), 0f)
    }
}
