package com.hereliesaz.MuralOverlay.rendering

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.GLUtils
import com.google.ar.core.Anchor
import com.google.ar.core.Camera
import com.hereliesaz.MuralOverlay.MuralState
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ObjectRenderer {

    fun draw(
        mesh: Mesh,
        shader: Shader,
        texture: Texture,
        depthTexture: Texture,
        camera: Camera,
        modelMatrix: FloatArray,
        state: MuralState
    ) {
        shader.use()

        val projectionMatrixLocation = GLES30.glGetUniformLocation(shader.getProgramId(), "u_Projection")
        val viewMatrixLocation = GLES30.glGetUniformLocation(shader.getProgramId(), "u_View")
        val modelMatrixLocation = GLES30.glGetUniformLocation(shader.getProgramId(), "u_Model")
        val opacityLocation = GLES30.glGetUniformLocation(shader.getProgramId(), "u_Opacity")
        val contrastLocation = GLES30.glGetUniformLocation(shader.getProgramId(), "u_Contrast")
        val saturationLocation = GLES30.glGetUniformLocation(shader.getProgramId(), "u_Saturation")

        val projectionMatrix = FloatArray(16)
        camera.getProjectionMatrix(projectionMatrix, 0, 0.1f, 100f)
        val viewMatrix = FloatArray(16)
        camera.getViewMatrix(viewMatrix, 0)
        GLES30.glUniformMatrix4fv(projectionMatrixLocation, 1, false, projectionMatrix, 0)
        GLES30.glUniformMatrix4fv(viewMatrixLocation, 1, false, viewMatrix, 0)
        GLES30.glUniformMatrix4fv(modelMatrixLocation, 1, false, modelMatrix, 0)
        GLES30.glUniform1f(opacityLocation, state.opacity)
        GLES30.glUniform1f(contrastLocation, state.contrast)
        GLES30.glUniform1f(saturationLocation, state.saturation)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture.getTextureId())
        val textureLocation = GLES30.glGetUniformLocation(shader.getProgramId(), "u_Texture")
        GLES30.glUniform1i(textureLocation, 0)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, depthTexture.getTextureId())
        val depthTextureLocation = GLES30.glGetUniformLocation(shader.getProgramId(), "u_DepthTexture")
        GLES30.glUniform1i(depthTextureLocation, 1)

        GLES30.glBindVertexArray(mesh.getVertexArrayId())
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, mesh.indexBuffer.size, GLES30.GL_UNSIGNED_SHORT, 0)
        GLES30.glBindVertexArray(0)
    }

    fun updateTexture(texture: Texture, bitmap: Bitmap) {
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture.getTextureId())
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
        GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
    }
}
