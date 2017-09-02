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
import com.google.ar.core.PointCloud
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.examples.kotlin.helloar.R

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Renders a point cloud.
 */
class PointCloudRenderer {

    private var mVbo: Int = 0
    private var mVboSize: Int = 0

    private var mProgramName: Int = 0
    private var mPositionAttribute: Int = 0
    private var mModelViewProjectionUniform: Int = 0
    private var mColorUniform: Int = 0
    private var mPointSizeUniform: Int = 0

    private var mNumPoints = 0

    // Keep track of the last point cloud rendered to avoid updating the VBO if point cloud
    // was not changed.
    private var mLastPointCloud: PointCloud? = null

    /**
     * Allocates and initializes OpenGL resources needed by the plane renderer.  Must be
     * called on the OpenGL thread, typically in
     * [GLSurfaceView.Renderer.onSurfaceCreated].
     *
     * @param context Needed to access shader source.
     */
    fun createOnGlThread(context: Context) {
        ShaderUtil.checkGLError(TAG, "before create")

        val buffers = IntArray(1)
        GLES20.glGenBuffers(1, buffers, 0)
        mVbo = buffers[0]
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, mVbo)

        mVboSize = INITIAL_BUFFER_POINTS * BYTES_PER_POINT
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, mVboSize, null, GLES20.GL_DYNAMIC_DRAW)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)

        ShaderUtil.checkGLError(TAG, "buffer alloc")

        val vertexShader = ShaderUtil.loadGLShader(TAG, context,
                GLES20.GL_VERTEX_SHADER, R.raw.point_cloud_vertex)
        val passthroughShader = ShaderUtil.loadGLShader(TAG, context,
                GLES20.GL_FRAGMENT_SHADER, R.raw.passthrough_fragment)

        mProgramName = GLES20.glCreateProgram()
        GLES20.glAttachShader(mProgramName, vertexShader)
        GLES20.glAttachShader(mProgramName, passthroughShader)
        GLES20.glLinkProgram(mProgramName)
        GLES20.glUseProgram(mProgramName)

        ShaderUtil.checkGLError(TAG, "program")

        mPositionAttribute = GLES20.glGetAttribLocation(mProgramName, "a_Position")
        mColorUniform = GLES20.glGetUniformLocation(mProgramName, "u_Color")
        mModelViewProjectionUniform = GLES20.glGetUniformLocation(
                mProgramName, "u_ModelViewProjection")
        mPointSizeUniform = GLES20.glGetUniformLocation(mProgramName, "u_PointSize")

        ShaderUtil.checkGLError(TAG, "program  params")
    }

    /**
     * Updates the OpenGL buffer contents to the provided point.  Repeated calls with the same
     * point cloud will be ignored.
     */
    fun update(cloud: PointCloud) {
        if (mLastPointCloud == cloud) {
            // Redundant call.
            return
        }

        ShaderUtil.checkGLError(TAG, "before update")

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, mVbo)
        mLastPointCloud = cloud

        // If the VBO is not large enough to fit the new point cloud, resize it.
        mNumPoints = cloud.points.remaining() / FLOATS_PER_POINT
        if (mNumPoints * BYTES_PER_POINT > mVboSize) {
            while (mNumPoints * BYTES_PER_POINT > mVboSize) {
                mVboSize *= 2
            }
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, mVboSize, null, GLES20.GL_DYNAMIC_DRAW)
        }
        GLES20.glBufferSubData(GLES20.GL_ARRAY_BUFFER, 0, mNumPoints * BYTES_PER_POINT,
                cloud.points)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)

        ShaderUtil.checkGLError(TAG, "after update")
    }

    /**
     * Renders the point cloud.
     *
     * @param pose the current point cloud pose, from [Frame.getPointCloudPose].
     * @param cameraView the camera view matrix for this frame, typically from
     * [Frame.getViewMatrix].
     * @param cameraPerspective the camera projection matrix for this frame, typically from
     * [Session.getProjectionMatrix].
     */
    fun draw(pose: Pose, cameraView: FloatArray, cameraPerspective: FloatArray) {
        val modelMatrix = FloatArray(16)
        pose.toMatrix(modelMatrix, 0)

        val modelView = FloatArray(16)
        val modelViewProjection = FloatArray(16)
        Matrix.multiplyMM(modelView, 0, cameraView, 0, modelMatrix, 0)
        Matrix.multiplyMM(modelViewProjection, 0, cameraPerspective, 0, modelView, 0)

        ShaderUtil.checkGLError(TAG, "Before draw")

        GLES20.glUseProgram(mProgramName)
        GLES20.glEnableVertexAttribArray(mPositionAttribute)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, mVbo)
        GLES20.glVertexAttribPointer(
                mPositionAttribute, 4, GLES20.GL_FLOAT, false, BYTES_PER_POINT, 0)
        GLES20.glUniform4f(mColorUniform, 31.0f / 255.0f, 188.0f / 255.0f, 210.0f / 255.0f, 1.0f)
        GLES20.glUniformMatrix4fv(mModelViewProjectionUniform, 1, false, modelViewProjection, 0)
        GLES20.glUniform1f(mPointSizeUniform, 5.0f)

        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, mNumPoints)
        GLES20.glDisableVertexAttribArray(mPositionAttribute)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)

        ShaderUtil.checkGLError(TAG, "Draw")
    }

    companion object {
        private val TAG = PointCloud::class.java.simpleName

        private const val BYTES_PER_FLOAT = java.lang.Float.SIZE / 8
        private const val FLOATS_PER_POINT = 4  // X,Y,Z,confidence.
        private const val BYTES_PER_POINT = BYTES_PER_FLOAT * FLOATS_PER_POINT
        private const val INITIAL_BUFFER_POINTS = 1000
    }
}
