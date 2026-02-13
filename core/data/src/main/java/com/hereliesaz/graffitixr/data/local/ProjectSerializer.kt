package com.hereliesaz.graffitixr.data.local

import com.hereliesaz.graffitixr.common.model.GraffitiProject
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProjectSerializer @Inject constructor() {

    // centralized JSON configuration to ensure all custom serializers (Uri, BlendMode) work
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
        isLenient = true
    }

    fun encode(project: GraffitiProject): String {
        return json.encodeToString(GraffitiProject.serializer(), project)
    }

    fun decode(data: String): GraffitiProject {
        return json.decodeFromString(GraffitiProject.serializer(), data)
    }
}