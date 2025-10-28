package com.hereliesaz.graffitixr.data

import kotlinx.serialization.Serializable

@Serializable
data class Fingerprint(
    val keypoints: List<@Serializable(with = KeyPointSerializer::class) org.opencv.core.KeyPoint>,
    @Serializable(with = MatSerializer::class)
    val descriptors: org.opencv.core.Mat
)
