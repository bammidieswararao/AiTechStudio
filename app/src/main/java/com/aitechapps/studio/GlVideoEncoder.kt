package com.aitechapps.studio

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import android.view.Surface
import java.io.FileDescriptor
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Writes the MP4. The AAC track (already encoded) is added together with the video track as soon as
 * the video encoder reports its format, then audio samples are interleaved by timestamp.
 */
class Mp4Mux(fd: FileDescriptor, private val audio: AacEncoder.Result?) {
    private val muxer = MediaMuxer(fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private var videoTrack = -1
    private var audioTrack = -1
    private var started = false
    private var released = false
    private var audioIndex = 0

    val isStarted: Boolean get() = started

    fun onVideoFormat(format: MediaFormat) {
        if (started) throw IOException("Video format changed twice")
        videoTrack = muxer.addTrack(format)
        if (audio != null) audioTrack = muxer.addTrack(audio.format)
        muxer.start()
        started = true
    }

    fun writeVideo(buf: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (!started) throw IOException("Muxer is not started")
        muxer.writeSampleData(videoTrack, buf, info)
        flushAudio(info.presentationTimeUs)
    }

    private fun flushAudio(upToUs: Long) {
        val a = audio ?: return
        val bi = MediaCodec.BufferInfo()
        while (audioIndex < a.samples.size && a.samples[audioIndex].ptsUs <= upToUs) {
            val s = a.samples[audioIndex++]
            bi.set(0, s.data.size, s.ptsUs, s.flags)
            muxer.writeSampleData(audioTrack, ByteBuffer.wrap(s.data), bi)
        }
    }

    fun finish() {
        if (released) return
        released = true
        try {
            if (started) {
                flushAudio(Long.MAX_VALUE)
                muxer.stop()
            }
        } finally {
            muxer.release()
        }
    }

    fun abort() {
        if (released) return
        released = true
        try { if (started) muxer.stop() } catch (_: Exception) { }
        try { muxer.release() } catch (_: Exception) { }
    }
}

/**
 * H.264 encoder fed through an input Surface. Each frame is a Bitmap drawn as a textured quad with
 * an explicit presentation timestamp (index / fps), so the video clock is exact no matter how long
 * a frame took to render.
 */
class GlVideoEncoder(
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val bitrate: Int,
    private val mux: Mp4Mux
) {
    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var program = 0
    private var texture = 0
    private var posLoc = 0
    private var texLoc = 0
    private var samplerLoc = 0
    private var quad: java.nio.FloatBuffer? = null
    private val info = MediaCodec.BufferInfo()
    private var finished = false

    fun start() {
        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
        fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        fmt.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        fmt.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
        fmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        try { fmt.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR) } catch (_: Exception) { }

        val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec = c
        c.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = c.createInputSurface()
        c.start()
        initEgl(inputSurface!!)
        initGl()
    }

    private fun initEgl(surface: Surface) {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) throw IOException("No EGL display")
        val ver = IntArray(2)
        if (!EGL14.eglInitialize(display, ver, 0, ver, 1)) throw IOException("EGL init failed")
        val recordable = 0x3142 // EGL_RECORDABLE_ANDROID
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            recordable, 1,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        if (!EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, num, 0) || num[0] < 1) throw IOException("No EGL config")
        context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        if (context == EGL14.EGL_NO_CONTEXT) throw IOException("EGL context failed")
        eglSurface = EGL14.eglCreateWindowSurface(display, configs[0], surface, intArrayOf(EGL14.EGL_NONE), 0)
        if (eglSurface == EGL14.EGL_NO_SURFACE) throw IOException("EGL surface failed")
        if (!EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) throw IOException("EGL make current failed")
    }

    private fun shader(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(s)
            GLES20.glDeleteShader(s)
            throw IOException("Shader error: $log")
        }
        return s
    }

    private fun initGl() {
        val vs = shader(GLES20.GL_VERTEX_SHADER, "attribute vec4 aPos; attribute vec2 aTex; varying vec2 vTex; void main(){ gl_Position = aPos; vTex = aTex; }")
        val fs = shader(GLES20.GL_FRAGMENT_SHADER, "precision mediump float; varying vec2 vTex; uniform sampler2D uTex; void main(){ gl_FragColor = texture2D(uTex, vTex); }")
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vs)
        GLES20.glAttachShader(program, fs)
        GLES20.glLinkProgram(program)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) throw IOException("Shader link failed")
        posLoc = GLES20.glGetAttribLocation(program, "aPos")
        texLoc = GLES20.glGetAttribLocation(program, "aTex")
        samplerLoc = GLES20.glGetUniformLocation(program, "uTex")

        // x, y, u, v. v is flipped because a Bitmap's first row is its top row.
        val data = floatArrayOf(
            -1f, -1f, 0f, 1f,
            1f, -1f, 1f, 1f,
            -1f, 1f, 0f, 0f,
            1f, 1f, 1f, 0f
        )
        quad = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(data); position(0) }

        val t = IntArray(1)
        GLES20.glGenTextures(1, t, 0)
        texture = t[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    /** Draws [bmp] as frame number [index] and lets the encoder take whatever is ready. */
    fun drawFrame(bmp: Bitmap, index: Int) {
        GLES20.glViewport(0, 0, width, height)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
        GLES20.glUniform1i(samplerLoc, 0)
        val q = quad!!
        q.position(0)
        GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 16, q)
        GLES20.glEnableVertexAttribArray(posLoc)
        q.position(2)
        GLES20.glVertexAttribPointer(texLoc, 2, GLES20.GL_FLOAT, false, 16, q)
        GLES20.glEnableVertexAttribArray(texLoc)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(posLoc)
        GLES20.glDisableVertexAttribArray(texLoc)
        EGLExt.eglPresentationTimeANDROID(display, eglSurface, index.toLong() * 1_000_000_000L / fps)
        if (!EGL14.eglSwapBuffers(display, eglSurface)) throw IOException("Could not present frame $index")
        drain(false)
    }

    /** Moves finished H.264 packets from the encoder into the muxer. */
    private fun drain(endOfStream: Boolean) {
        val c = codec ?: return
        var stall = 0
        while (true) {
            val oi = c.dequeueOutputBuffer(info, if (endOfStream) 10_000 else 0)
            if (oi == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream) return
                if (++stall > 800) throw IOException("Video encoder stopped responding")
            } else if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                mux.onVideoFormat(c.outputFormat)
            } else if (oi >= 0) {
                stall = 0
                val ob = c.getOutputBuffer(oi)
                if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0
                if (ob != null && info.size > 0) {
                    ob.position(info.offset)
                    ob.limit(info.offset + info.size)
                    mux.writeVideo(ob, info)
                }
                c.releaseOutputBuffer(oi, false)
                if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return
            }
        }
    }

    fun finish() {
        if (finished) return
        finished = true
        try {
            codec?.signalEndOfInputStream()
            drain(true)
        } finally {
            release()
        }
    }

    fun abort() {
        finished = true
        release()
    }

    private fun release() {
        try { codec?.stop() } catch (_: Exception) { }
        try { codec?.release() } catch (_: Exception) { }
        codec = null
        if (display != EGL14.EGL_NO_DISPLAY) {
            try { EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT) } catch (_: Exception) { }
            if (eglSurface != EGL14.EGL_NO_SURFACE) try { EGL14.eglDestroySurface(display, eglSurface) } catch (_: Exception) { }
            if (context != EGL14.EGL_NO_CONTEXT) try { EGL14.eglDestroyContext(display, context) } catch (_: Exception) { }
            try { EGL14.eglReleaseThread() } catch (_: Exception) { }
            try { EGL14.eglTerminate(display) } catch (_: Exception) { }
        }
        display = EGL14.EGL_NO_DISPLAY
        context = EGL14.EGL_NO_CONTEXT
        eglSurface = EGL14.EGL_NO_SURFACE
        try { inputSurface?.release() } catch (_: Exception) { }
        inputSurface = null
    }
}
