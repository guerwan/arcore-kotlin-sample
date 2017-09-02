/*
 * Copyright 2017 Google Inc. All Rights Reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.ar.core.examples.kotlin.helloar.rendering

import com.google.ar.core.examples.kotlin.helloar.R

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.GLES20
import android.opengl.GLUtils
import android.opengl.Matrix
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.nio.ShortBuffer
import de.javagl.Obj
import de.javagl.ObjData
import de.javagl.ObjReader
import de.javagl.ObjUtils

/**
 * Renders an object loaded from an OBJ file in OpenGL.
 */
class ObjectRenderer {

    /**
     * Blend mode.
     *
     * @see .setBlendMode
     */
    enum class BlendMode {
        /** Multiplies the destination color by the source alpha.  */
        Shadow,
        /** Normal alpha blending.  */
        Grid
    }

    private val mViewLightDirection = FloatArray(4)

    // Object vertex buffer variables.
    private var mVertexBufferId: Int = 0
    private var mVerticesBaseAddress: Int = 0
    private var mTexCoordsBaseAddress: Int = 0
    private var mNormalsBaseAddress: Int = 0
    private var mIndexBufferId: Int = 0
    private var mIndexCount: Int = 0

    private var mProgram: Int = 0
    private val mTextures = IntArray(1)

    // Shader location: model view projection matrix.
    private var mModelViewUniform: Int = 0
    private var mModelViewProjectionUniform: Int = 0

    // Shader location: object attributes.
    private var mPositionAttribute: Int = 0
    private var mNormalAttribute: Int = 0
    private var mTexCoordAttribute: Int = 0

    // Shader location: texture sampler.
    private var mTextureUniform: Int = 0

    // Shader location: environment properties.
    private var mLightingParametersUniform: Int = 0

    // Shader location: material properties.
    private var mMaterialParametersUniform: Int = 0

    private var mBlendMode: BlendMode? = null

    // Temporary matrices allocated here to reduce number of allocations for each frame.
    private val mModelMatrix = FloatArray(16)
    private val mModelViewMatrix = FloatArray(16)
    private val mModelViewProjectionMatrix = FloatArray(16)

    // Set some default material properties to use for lighting.
    private var mAmbient = 0.3f
    private var mDiffuse = 1.0f
    private var mSpecular = 1.0f
    private var mSpecularPower = 6.0f

    /**
     * Creates and initializes OpenGL resources needed for rendering the model.
     *
     * @param context Context for loading the shader and below-named model and texture assets.
     * @param objAssetName  Name of the OBJ file containing the model geometry.
     * @param diffuseTextureAssetName  Name of the PNG file containing the diffuse texture map.
     */
    @Throws(IOException::class)
    fun createOnGlThread(context: Context, objAssetName: String,
                         diffuseTextureAssetName: String) {
        // Read the texture.
        val textureBitmap = BitmapFactory.decodeStream(
                context.assets.open(diffuseTextureAssetName))

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glGenTextures(mTextures.size, mTextures, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mTextures[0])

        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR_MIPMAP_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, textureBitmap, 0)
        GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)

        textureBitmap.recycle()

        ShaderUtil.checkGLError(TAG, "Texture loading")

        // Read the obj file.
        val objInputStream = context.assets.open(objAssetName)
        var obj = ObjReader.read(objInputStream)

        // Prepare the Obj so that its structure is suitable for
        // rendering with OpenGL:
        // 1. Triangulate it
        // 2. Make sure that texture coordinates are not ambiguous
        // 3. Make sure that normals are not ambiguous
        // 4. Convert it to single-indexed data
        obj = ObjUtils.convertToRenderable(obj)

        // OpenGL does not use Java arrays. ByteBuffers are used instead to provide data in a format
        // that OpenGL understands.

        // Obtain the data from the OBJ, as direct buffers:
        val wideIndices = ObjData.getFaceVertexIndices(obj, 3)
        val vertices = ObjData.getVertices(obj)
        val texCoords = ObjData.getTexCoords(obj, 2)
        val normals = ObjData.getNormals(obj)

        // Convert int indices to shorts for GL ES 2.0 compatibility
        val indices = ByteBuffer.allocateDirect(2 * wideIndices.limit())
                .order(ByteOrder.nativeOrder()).asShortBuffer()
        while (wideIndices.hasRemaining()) {
            indices.put(wideIndices.get().toShort())
        }
        indices.rewind()

        val buffers = IntArray(2)
        GLES20.glGenBuffers(2, buffers, 0)
        mVertexBufferId = buffers[0]
        mIndexBufferId = buffers[1]

        // Load vertex buffer
        mVerticesBaseAddress = 0
        mTexCoordsBaseAddress = mVerticesBaseAddress + 4 * vertices.limit()
        mNormalsBaseAddress = mTexCoordsBaseAddress + 4 * texCoords.limit()
        val totalBytes = mNormalsBaseAddress + 4 * normals.limit()

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, mVertexBufferId)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, totalBytes, null, GLES20.GL_STATIC_DRAW)
        GLES20.glBufferSubData(
                GLES20.GL_ARRAY_BUFFER, mVerticesBaseAddress, 4 * vertices.limit(), vertices)
        GLES20.glBufferSubData(
                GLES20.GL_ARRAY_BUFFER, mTexCoordsBaseAddress, 4 * texCoords.limit(), texCoords)
        GLES20.glBufferSubData(
                GLES20.GL_ARRAY_BUFFER, mNormalsBaseAddress, 4 * normals.limit(), normals)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)

        // Load index buffer
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, mIndexBufferId)
        mIndexCount = indices.limit()
        GLES20.glBufferData(
                GLES20.GL_ELEMENT_ARRAY_BUFFER, 2 * mIndexCount, indices, GLES20.GL_STATIC_DRAW)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)

        ShaderUtil.checkGLError(TAG, "OBJ buffer load")

        val vertexShader = ShaderUtil.loadGLShader(TAG, context,
                GLES20.GL_VERTEX_SHADER, R.raw.object_vertex)
        val fragmentShader = ShaderUtil.loadGLShader(TAG, context,
                GLES20.GL_FRAGMENT_SHADER, R.raw.object_fragment)

        mProgram = GLES20.glCreateProgram()
        GLES20.glAttachShader(mProgram, vertexShader)
        GLES20.glAttachShader(mProgram, fragmentShader)
        GLES20.glLinkProgram(mProgram)
        GLES20.glUseProgram(mProgram)

        ShaderUtil.checkGLError(TAG, "Program creation")

        mModelViewUniform = GLES20.glGetUniformLocation(mProgram, "u_ModelView")
        mModelViewProjectionUniform = GLES20.glGetUniformLocation(mProgram, "u_ModelViewProjection")

        mPositionAttribute = GLES20.glGetAttribLocation(mProgram, "a_Position")
        mNormalAttribute = GLES20.glGetAttribLocation(mProgram, "a_Normal")
        mTexCoordAttribute = GLES20.glGetAttribLocation(mProgram, "a_TexCoord")

        mTextureUniform = GLES20.glGetUniformLocation(mProgram, "u_Texture")

        mLightingParametersUniform = GLES20.glGetUniformLocation(mProgram, "u_LightingParameters")
        mMaterialParametersUniform = GLES20.glGetUniformLocation(mProgram, "u_MaterialParameters")

        ShaderUtil.checkGLError(TAG, "Program parameters")

        Matrix.setIdentityM(mModelMatrix, 0)
    }

    /**
     * Selects the blending mode for rendering.
     *
     * @param blendMode The blending mode.  Null indicates no blending (opaque rendering).
     */
    fun setBlendMode(blendMode: BlendMode) {
        mBlendMode = blendMode
    }

    /**
     * Updates the object model matrix and applies scaling.
     *
     * @param modelMatrix A 4x4 model-to-world transformation matrix, stored in column-major order.
     * @param scaleFactor A separate scaling factor to apply before the `modelMatrix`.
     * @see android.opengl.Matrix
     */
    fun updateModelMatrix(modelMatrix: FloatArray, scaleFactor: Float) {
        val scaleMatrix = FloatArray(16)
        Matrix.setIdentityM(scaleMatrix, 0)
        scaleMatrix[0] = scaleFactor
        scaleMatrix[5] = scaleFactor
        scaleMatrix[10] = scaleFactor
        Matrix.multiplyMM(mModelMatrix, 0, modelMatrix, 0, scaleMatrix, 0)
    }

    /**
     * Sets the surface characteristics of the rendered model.
     *
     * @param ambient  Intensity of non-directional surface illumination.
     * @param diffuse  Diffuse (matte) surface reflectivity.
     * @param specular  Specular (shiny) surface reflectivity.
     * @param specularPower  Surface shininess.  Larger values result in a smaller, sharper
     * specular highlight.
     */
    fun setMaterialProperties(
            ambient: Float, diffuse: Float, specular: Float, specularPower: Float) {
        mAmbient = ambient
        mDiffuse = diffuse
        mSpecular = specular
        mSpecularPower = specularPower
    }

    /**
     * Draws the model.
     *
     * @param cameraView  A 4x4 view matrix, in column-major order.
     * @param cameraPerspective  A 4x4 projection matrix, in column-major order.
     * @param lightIntensity  Illumination intensity.  Combined with diffuse and specular material
     * properties.
     * @see .setBlendMode
     * @see .updateModelMatrix
     * @see .setMaterialProperties
     * @see android.opengl.Matrix
     */
    fun draw(cameraView: FloatArray, cameraPerspective: FloatArray, lightIntensity: Float) {

        ShaderUtil.checkGLError(TAG, "Before draw")

        // Build the ModelView and ModelViewProjection matrices
        // for calculating object position and light.
        Matrix.multiplyMM(mModelViewMatrix, 0, cameraView, 0, mModelMatrix, 0)
        Matrix.multiplyMM(mModelViewProjectionMatrix, 0, cameraPerspective, 0, mModelViewMatrix, 0)

        GLES20.glUseProgram(mProgram)

        // Set the lighting environment properties.
        Matrix.multiplyMV(mViewLightDirection, 0, mModelViewMatrix, 0, LIGHT_DIRECTION, 0)
        normalizeVec3(mViewLightDirection)
        GLES20.glUniform4f(mLightingParametersUniform,
                mViewLightDirection[0], mViewLightDirection[1], mViewLightDirection[2], lightIntensity)

        // Set the object material properties.
        GLES20.glUniform4f(mMaterialParametersUniform, mAmbient, mDiffuse, mSpecular,
                mSpecularPower)

        // Attach the object texture.
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mTextures[0])
        GLES20.glUniform1i(mTextureUniform, 0)

        // Set the vertex attributes.
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, mVertexBufferId)

        GLES20.glVertexAttribPointer(
                mPositionAttribute, COORDS_PER_VERTEX, GLES20.GL_FLOAT, false, 0, mVerticesBaseAddress)
        GLES20.glVertexAttribPointer(
                mNormalAttribute, 3, GLES20.GL_FLOAT, false, 0, mNormalsBaseAddress)
        GLES20.glVertexAttribPointer(
                mTexCoordAttribute, 2, GLES20.GL_FLOAT, false, 0, mTexCoordsBaseAddress)

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)

        // Set the ModelViewProjection matrix in the shader.
        GLES20.glUniformMatrix4fv(
                mModelViewUniform, 1, false, mModelViewMatrix, 0)
        GLES20.glUniformMatrix4fv(
                mModelViewProjectionUniform, 1, false, mModelViewProjectionMatrix, 0)

        // Enable vertex arrays
        GLES20.glEnableVertexAttribArray(mPositionAttribute)
        GLES20.glEnableVertexAttribArray(mNormalAttribute)
        GLES20.glEnableVertexAttribArray(mTexCoordAttribute)

        if (mBlendMode != null) {
            GLES20.glDepthMask(false)
            GLES20.glEnable(GLES20.GL_BLEND)
            when (mBlendMode) {
                ObjectRenderer.BlendMode.Shadow ->
                    // Multiplicative blending function for Shadow.
                    GLES20.glBlendFunc(GLES20.GL_ZERO, GLES20.GL_ONE_MINUS_SRC_ALPHA)
                ObjectRenderer.BlendMode.Grid ->
                    // Grid, additive blending function.
                    GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            }
        }

        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, mIndexBufferId)
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, mIndexCount, GLES20.GL_UNSIGNED_SHORT, 0)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)

        if (mBlendMode != null) {
            GLES20.glDisable(GLES20.GL_BLEND)
            GLES20.glDepthMask(true)
        }

        // Disable vertex arrays
        GLES20.glDisableVertexAttribArray(mPositionAttribute)
        GLES20.glDisableVertexAttribArray(mNormalAttribute)
        GLES20.glDisableVertexAttribArray(mTexCoordAttribute)

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)

        ShaderUtil.checkGLError(TAG, "After draw")
    }

    companion object {
        private val TAG = ObjectRenderer::class.java.simpleName

        private val COORDS_PER_VERTEX = 3

        // Note: the last component must be zero to avoid applying the translational part of the matrix.
        private val LIGHT_DIRECTION = floatArrayOf(0.0f, 1.0f, 0.0f, 0.0f)

        fun normalizeVec3(v: FloatArray) {
            val reciprocalLength = 1.0f / Math.sqrt((v[0] * v[0] + v[1] * v[1] + v[2] * v[2]).toDouble()).toFloat()
            v[0] *= reciprocalLength
            v[1] *= reciprocalLength
            v[2] *= reciprocalLength
        }
    }
}
