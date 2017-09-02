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

import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.examples.kotlin.helloar.R

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import java.util.ArrayList
import java.util.Collections
import java.util.Comparator
import java.util.HashMap
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Renders the detected AR planes.
 */
class PlaneRenderer {

    private var mPlaneProgram: Int = 0
    private val mTextures = IntArray(1)

    private var mPlaneXZPositionAlphaAttribute: Int = 0

    private var mPlaneModelUniform: Int = 0
    private var mPlaneModelViewProjectionUniform: Int = 0
    private var mTextureUniform: Int = 0
    private var mLineColorUniform: Int = 0
    private var mDotColorUniform: Int = 0
    private var mGridControlUniform: Int = 0
    private var mPlaneUvMatrixUniform: Int = 0

    private var mVertexBuffer = ByteBuffer.allocateDirect(INITIAL_VERTEX_BUFFER_SIZE_BYTES)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
    private var mIndexBuffer = ByteBuffer.allocateDirect(INITIAL_INDEX_BUFFER_SIZE_BYTES)
            .order(ByteOrder.nativeOrder()).asShortBuffer()

    // Temporary lists/matrices allocated here to reduce number of allocations for each frame.
    private val mModelMatrix = FloatArray(16)
    private val mModelViewMatrix = FloatArray(16)
    private val mModelViewProjectionMatrix = FloatArray(16)
    private val mPlaneColor = FloatArray(4)
    private val mPlaneAngleUvMatrix = FloatArray(4) // 2x2 rotation matrix applied to uv coords.

    private val mPlaneIndexMap = HashMap<Plane, Int>()

    /**
     * Allocates and initializes OpenGL resources needed by the plane renderer.  Must be
     * called on the OpenGL thread, typically in
     * [GLSurfaceView.Renderer.onSurfaceCreated].
     *
     * @param context Needed to access shader source and texture PNG.
     * @param gridDistanceTextureName  Name of the PNG file containing the grid texture.
     */
    @Throws(IOException::class)
    fun createOnGlThread(context: Context, gridDistanceTextureName: String) {
        val vertexShader = ShaderUtil.loadGLShader(TAG, context,
                GLES20.GL_VERTEX_SHADER, R.raw.plane_vertex)
        val passthroughShader = ShaderUtil.loadGLShader(TAG, context,
                GLES20.GL_FRAGMENT_SHADER, R.raw.plane_fragment)

        mPlaneProgram = GLES20.glCreateProgram()
        GLES20.glAttachShader(mPlaneProgram, vertexShader)
        GLES20.glAttachShader(mPlaneProgram, passthroughShader)
        GLES20.glLinkProgram(mPlaneProgram)
        GLES20.glUseProgram(mPlaneProgram)

        ShaderUtil.checkGLError(TAG, "Program creation")

        // Read the texture.
        val textureBitmap = BitmapFactory.decodeStream(
                context.assets.open(gridDistanceTextureName))

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

        ShaderUtil.checkGLError(TAG, "Texture loading")

        mPlaneXZPositionAlphaAttribute = GLES20.glGetAttribLocation(mPlaneProgram,
                "a_XZPositionAlpha")

        mPlaneModelUniform = GLES20.glGetUniformLocation(mPlaneProgram, "u_Model")
        mPlaneModelViewProjectionUniform = GLES20.glGetUniformLocation(mPlaneProgram, "u_ModelViewProjection")
        mTextureUniform = GLES20.glGetUniformLocation(mPlaneProgram, "u_Texture")
        mLineColorUniform = GLES20.glGetUniformLocation(mPlaneProgram, "u_lineColor")
        mDotColorUniform = GLES20.glGetUniformLocation(mPlaneProgram, "u_dotColor")
        mGridControlUniform = GLES20.glGetUniformLocation(mPlaneProgram, "u_gridControl")
        mPlaneUvMatrixUniform = GLES20.glGetUniformLocation(mPlaneProgram, "u_PlaneUvMatrix")

        ShaderUtil.checkGLError(TAG, "Program parameters")
    }

    /**
     * Updates the plane model transform matrix and extents.
     */
    private fun updatePlaneParameters(planeMatrix: FloatArray, extentX: Float, extentZ: Float,
                                      boundary: FloatBuffer?) {
        System.arraycopy(planeMatrix, 0, mModelMatrix, 0, 16)
        if (boundary == null) {
            mVertexBuffer.limit(0)
            mIndexBuffer.limit(0)
            return
        }

        // Generate a new set of vertices and a corresponding triangle strip index set so that
        // the plane boundary polygon has a fading edge. This is done by making a copy of the
        // boundary polygon vertices and scaling it down around center to push it inwards. Then
        // the index buffer is setup accordingly.
        boundary.rewind()
        val boundaryVertices = boundary.limit() / 2
        val numVertices: Int
        val numIndices: Int

        numVertices = boundaryVertices * VERTS_PER_BOUNDARY_VERT
        // drawn as GL_TRIANGLE_STRIP with 3n-2 triangles (n-2 for fill, 2n for perimeter).
        numIndices = boundaryVertices * INDICES_PER_BOUNDARY_VERT

        if (mVertexBuffer.capacity() < numVertices * COORDS_PER_VERTEX) {
            var size = mVertexBuffer.capacity()
            while (size < numVertices * COORDS_PER_VERTEX) {
                size *= 2
            }
            mVertexBuffer = ByteBuffer.allocateDirect(BYTES_PER_FLOAT * size)
                    .order(ByteOrder.nativeOrder()).asFloatBuffer()
        }
        mVertexBuffer.rewind()
        mVertexBuffer.limit(numVertices * COORDS_PER_VERTEX)


        if (mIndexBuffer.capacity() < numIndices) {
            var size = mIndexBuffer.capacity()
            while (size < numIndices) {
                size *= 2
            }
            mIndexBuffer = ByteBuffer.allocateDirect(BYTES_PER_SHORT * size)
                    .order(ByteOrder.nativeOrder()).asShortBuffer()
        }
        mIndexBuffer.rewind()
        mIndexBuffer.limit(numIndices)

        // Note: when either dimension of the bounding box is smaller than 2*FADE_RADIUS_M we
        // generate a bunch of 0-area triangles.  These don't get rendered though so it works
        // out ok.
        val xScale = Math.max((extentX - 2 * FADE_RADIUS_M) / extentX, 0.0f)
        val zScale = Math.max((extentZ - 2 * FADE_RADIUS_M) / extentZ, 0.0f)

        while (boundary.hasRemaining()) {
            val x = boundary.get()
            val z = boundary.get()
            mVertexBuffer.put(x)
            mVertexBuffer.put(z)
            mVertexBuffer.put(0.0f)
            mVertexBuffer.put(x * xScale)
            mVertexBuffer.put(z * zScale)
            mVertexBuffer.put(1.0f)
        }

        // step 1, perimeter
        mIndexBuffer.put(((boundaryVertices - 1) * 2).toShort())
        for (i in 0..boundaryVertices - 1) {
            mIndexBuffer.put((i * 2).toShort())
            mIndexBuffer.put((i * 2 + 1).toShort())
        }
        mIndexBuffer.put(1.toShort())
        // This leaves us on the interior edge of the perimeter between the inset vertices
        // for boundary verts n-1 and 0.

        // step 2, interior:
        for (i in 1..boundaryVertices / 2 - 1) {
            mIndexBuffer.put(((boundaryVertices - 1 - i) * 2 + 1).toShort())
            mIndexBuffer.put((i * 2 + 1).toShort())
        }
        if (boundaryVertices % 2 != 0) {
            mIndexBuffer.put((boundaryVertices / 2 * 2 + 1).toShort())
        }
    }

    private fun draw(cameraView: FloatArray, cameraPerspective: FloatArray) {
        // Build the ModelView and ModelViewProjection matrices
        // for calculating cube position and light.
        Matrix.multiplyMM(mModelViewMatrix, 0, cameraView, 0, mModelMatrix, 0)
        Matrix.multiplyMM(mModelViewProjectionMatrix, 0, cameraPerspective, 0, mModelViewMatrix, 0)

        // Set the position of the plane
        mVertexBuffer.rewind()
        GLES20.glVertexAttribPointer(
                mPlaneXZPositionAlphaAttribute, COORDS_PER_VERTEX, GLES20.GL_FLOAT, false,
                BYTES_PER_FLOAT * COORDS_PER_VERTEX, mVertexBuffer)

        // Set the Model and ModelViewProjection matrices in the shader.
        GLES20.glUniformMatrix4fv(mPlaneModelUniform, 1, false, mModelMatrix, 0)
        GLES20.glUniformMatrix4fv(
                mPlaneModelViewProjectionUniform, 1, false, mModelViewProjectionMatrix, 0)

        mIndexBuffer.rewind()
        GLES20.glDrawElements(GLES20.GL_TRIANGLE_STRIP, mIndexBuffer.limit(),
                GLES20.GL_UNSIGNED_SHORT, mIndexBuffer)
        ShaderUtil.checkGLError(TAG, "Drawing plane")
    }

    internal class SortablePlane(val mDistance: Float, val mPlane: Plane)

    /**
     * Draws the collection of tracked planes, with closer planes hiding more distant ones.
     *
     * @param allPlanes The collection of planes to draw.
     * @param cameraPose The pose of the camera, as returned by [Frame.getPose]
     * @param cameraPerspective The projection matrix, as returned by
     * [Session.getProjectionMatrix]
     */
    fun drawPlanes(allPlanes: Collection<Plane>, cameraPose: Pose,
                   cameraPerspective: FloatArray) {
        // Planes must be sorted by distance from camera so that we draw closer planes first, and
        // they occlude the farther planes.
        val sortedPlanes = ArrayList<SortablePlane>()
        val normal = FloatArray(3)
        val cameraX = cameraPose.tx()
        val cameraY = cameraPose.ty()
        val cameraZ = cameraPose.tz()
        for (plane in allPlanes) {
            if (plane.type != com.google.ar.core.Plane.Type.HORIZONTAL_UPWARD_FACING || plane.trackingState != Plane.TrackingState.TRACKING) {
                continue
            }

            val center = plane.centerPose
            // Get transformed Y axis of plane's coordinate system.
            center.getTransformedAxis(1, 1.0f, normal, 0)
            // Compute dot product of plane's normal with vector from camera to plane center.
            val distance = (cameraX - center.tx()) * normal[0] +
                    (cameraY - center.ty()) * normal[1] + (cameraZ - center.tz()) * normal[2]
            if (distance < 0) {  // Plane is back-facing.
                continue
            }
            sortedPlanes.add(SortablePlane(distance, plane))
        }
        Collections.sort(sortedPlanes) { a, b -> java.lang.Float.compare(a.mDistance, b.mDistance) }


        val cameraView = FloatArray(16)
        cameraPose.inverse().toMatrix(cameraView, 0)

        // Planes are drawn with additive blending, masked by the alpha channel for occlusion.

        // Start by clearing the alpha channel of the color buffer to 1.0.
        GLES20.glClearColor(1f, 1f, 1f, 1f)
        GLES20.glColorMask(false, false, false, true)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glColorMask(true, true, true, true)

        // Disable depth write.
        GLES20.glDepthMask(false)

        // Additive blending, masked by alpha chanel, clearing alpha channel.
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFuncSeparate(
                GLES20.GL_DST_ALPHA, GLES20.GL_ONE, // RGB (src, dest)
                GLES20.GL_ZERO, GLES20.GL_ONE_MINUS_SRC_ALPHA)  // ALPHA (src, dest)

        // Set up the shader.
        GLES20.glUseProgram(mPlaneProgram)

        // Attach the texture.
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mTextures[0])
        GLES20.glUniform1i(mTextureUniform, 0)

        // Shared fragment uniforms.
        GLES20.glUniform4fv(mGridControlUniform, 1, GRID_CONTROL, 0)

        // Enable vertex arrays
        GLES20.glEnableVertexAttribArray(mPlaneXZPositionAlphaAttribute)

        ShaderUtil.checkGLError(TAG, "Setting up to draw planes")

        for (sortedPlane in sortedPlanes) {
            val plane = sortedPlane.mPlane
            val planeMatrix = FloatArray(16)
            plane.centerPose.toMatrix(planeMatrix, 0)

            updatePlaneParameters(planeMatrix, plane.extentX,
                    plane.extentZ, plane.planePolygon)

            // Get plane index. Keep a map to assign same indices to same planes.
            var planeIndex: Int? = mPlaneIndexMap[plane]
            if (planeIndex == null) {
                planeIndex = Integer.valueOf(mPlaneIndexMap.size)
                mPlaneIndexMap.put(plane, planeIndex)
            }

            // Set plane color. Computed deterministically from the Plane index.
            val colorIndex = planeIndex!! % PLANE_COLORS_RGBA.size
            colorRgbaToFloat(mPlaneColor, PLANE_COLORS_RGBA[colorIndex])
            GLES20.glUniform4fv(mLineColorUniform, 1, mPlaneColor, 0)
            GLES20.glUniform4fv(mDotColorUniform, 1, mPlaneColor, 0)

            // Each plane will have its own angle offset from others, to make them easier to
            // distinguish. Compute a 2x2 rotation matrix from the angle.
            val angleRadians = planeIndex * 0.144f
            val uScale = DOTS_PER_METER
            val vScale = DOTS_PER_METER * EQUILATERAL_TRIANGLE_SCALE
            mPlaneAngleUvMatrix[0] = +Math.cos(angleRadians.toDouble()).toFloat() * uScale
            mPlaneAngleUvMatrix[1] = -Math.sin(angleRadians.toDouble()).toFloat() * uScale
            mPlaneAngleUvMatrix[2] = +Math.sin(angleRadians.toDouble()).toFloat() * vScale
            mPlaneAngleUvMatrix[3] = +Math.cos(angleRadians.toDouble()).toFloat() * vScale
            GLES20.glUniformMatrix2fv(mPlaneUvMatrixUniform, 1, false, mPlaneAngleUvMatrix, 0)

            draw(cameraView, cameraPerspective)
        }

        // Clean up the state we set
        GLES20.glDisableVertexAttribArray(mPlaneXZPositionAlphaAttribute)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glDepthMask(true)

        ShaderUtil.checkGLError(TAG, "Cleaning up after drawing planes")
    }

    companion object {
        private val TAG = PlaneRenderer::class.java.simpleName

        private const val BYTES_PER_FLOAT = java.lang.Float.SIZE / 8
        private const val BYTES_PER_SHORT = java.lang.Short.SIZE / 8
        private const val COORDS_PER_VERTEX = 3 // x, z, alpha

        private const val VERTS_PER_BOUNDARY_VERT = 2
        private const val INDICES_PER_BOUNDARY_VERT = 3
        private const val INITIAL_BUFFER_BOUNDARY_VERTS = 64

        private const val INITIAL_VERTEX_BUFFER_SIZE_BYTES =
                BYTES_PER_FLOAT * COORDS_PER_VERTEX * VERTS_PER_BOUNDARY_VERT *
                        INITIAL_BUFFER_BOUNDARY_VERTS

        private const val INITIAL_INDEX_BUFFER_SIZE_BYTES =
                BYTES_PER_SHORT * INDICES_PER_BOUNDARY_VERT * INDICES_PER_BOUNDARY_VERT *
                        INITIAL_BUFFER_BOUNDARY_VERTS

        private const val FADE_RADIUS_M = 0.25f
        private const val DOTS_PER_METER = 10.0f
        private val EQUILATERAL_TRIANGLE_SCALE = (1 / Math.sqrt(3.0)).toFloat()

        // Using the "signed distance field" approach to render sharp lines and circles.
        // {dotThreshold, lineThreshold, lineFadeSpeed, occlusionScale}
        // dotThreshold/lineThreshold: red/green intensity above which dots/lines are present
        // lineFadeShrink:  lines will fade in between alpha = 1-(1/lineFadeShrink) and 1.0
        // occlusionShrink: occluded planes will fade out between alpha = 0 and 1/occlusionShrink
        private val GRID_CONTROL = floatArrayOf(0.2f, 0.4f, 2.0f, 1.5f)

        private fun colorRgbaToFloat(planeColor: FloatArray, colorRgba: Int) {
            planeColor[0] = (colorRgba shr 24 and 0xff).toFloat() / 255.0f
            planeColor[1] = (colorRgba shr 16 and 0xff).toFloat() / 255.0f
            planeColor[2] = (colorRgba shr 8 and 0xff).toFloat() / 255.0f
            planeColor[3] = (colorRgba shr 0 and 0xff).toFloat() / 255.0f
        }

        private val PLANE_COLORS_RGBA = intArrayOf(0xFFFFFFFF.toInt(), 0xF44336FF.toInt(),
                0xE91E63FF.toInt(), 0x9C27B0FF.toInt(),
                0x673AB7FF, 0x3F51B5FF, 0x2196F3FF, 0x03A9F4FF, 0x00BCD4FF,
                0x009688FF, 0x4CAF50FF, 0x8BC34AFF.toInt(), 0xCDDC39FF.toInt(),
                0xFFEB3BFF.toInt(), 0xFFC107FF.toInt(), 0xFF9800FF.toInt())
    }
}
