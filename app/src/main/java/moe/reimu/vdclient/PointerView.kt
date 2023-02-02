package moe.reimu.vdclient

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.opengl.EGL14
import android.opengl.EGLExt
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import java.nio.Buffer
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class PointerView(context: Context, attrs: AttributeSet) : GLSurfaceView(context, attrs) {
    class Renderer : GLSurfaceView.Renderer {
        companion object {
            const val vertShaderSource = """attribute vec4 vertex;
uniform vec2 viewportSize;
varying vec2 texCoord;
void main()
{
    gl_Position.x = 2.0 * (vertex.x + 0.5) / viewportSize.x;
    gl_Position.y = 2.0 * (vertex.y + 0.5) / viewportSize.y;
    gl_Position.z = 0.0;
    gl_Position.w = 1.0;
    texCoord = vertex.zw;
}"""
            const val fragShaderSource = """precision highp float;
uniform sampler2D texture;
varying vec2 texCoord;
void main()
{
    vec4 color = texture2D(texture, texCoord);
    gl_FragColor = vec4(color.b, color.g, color.r, color.a);
}"""
        }

        private var glProgram = 0
        private var glAttribVertex = 0
        private var glUniformViewportSize = 0

        private var pointerX = AtomicInteger()
        private var pointerY = AtomicInteger()
        private var pointerVisible = AtomicBoolean()

        private val imageLock = Object()
        private val imageCache = mutableMapOf<Int, Pair<Pair<Int, Int>, ByteBuffer>>()

        /**
         * 0 if no image has been set
         */
        private var imageCrc32 = 0
        private var imageUpdated = false
        private var textureId = 0
        private var textureWidth = 0
        private var textureHeight = 0

        private var viewportWidth = 0
        private var viewportHeight = 0

        private val vertexBuffer = FloatBuffer.wrap(FloatArray(4 * 4))

        private fun loadShader(type: Int, source: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            return shader
        }

        fun setImage(crc32: Int, pngBuffer: ByteBuffer) {
            synchronized(imageLock) {
                if (imageCache.count() > 60) {
                    imageCache.clear()
                }

                if (!imageCache.containsKey(crc32)) {
                    val bitmap = BitmapFactory.decodeByteArray(
                        pngBuffer.array(), pngBuffer.arrayOffset(), pngBuffer.limit()
                    )
                    val width = bitmap.width
                    val height = bitmap.height
                    val buffer = ByteBuffer.allocate(bitmap.byteCount)
                    bitmap.copyPixelsToBuffer(buffer)
                    imageCache[crc32] = Pair(Pair(width, height), buffer)
                }

                imageCrc32 = crc32
                imageUpdated = true
            }
        }

        fun setPosition(x: Int, y: Int, visible: Boolean) {
            pointerX.set(x)
            pointerY.set(y)
            pointerVisible.set(visible)
        }

        override fun onSurfaceCreated(gl: GL10, config: EGLConfig) {
            glProgram = GLES20.glCreateProgram()

            val vertShader = loadShader(GLES20.GL_VERTEX_SHADER, vertShaderSource)
            val fragShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragShaderSource)

            GLES20.glAttachShader(glProgram, vertShader)
            GLES20.glAttachShader(glProgram, fragShader)
            GLES20.glLinkProgram(glProgram)

            glAttribVertex = GLES20.glGetAttribLocation(glProgram, "vertex")
            glUniformViewportSize = GLES20.glGetUniformLocation(glProgram, "viewportSize")
        }

        override fun onSurfaceChanged(gl: GL10, width: Int, height: Int) {
            GLES20.glViewport(0, 0, width, height)
            viewportWidth = width
            viewportHeight = height
        }

        override fun onDrawFrame(gl: GL10) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            synchronized(imageLock) {
                if (imageUpdated) {
                    val (imageSize, imageBuffer) = imageCache[imageCrc32]!!
                    val (imageWidth, imageHeight) = imageSize

                    if (textureId == 0 || (imageWidth > textureWidth || imageHeight > textureHeight)) {
                        // Re-allocate texture
                        if (textureId != 0) {
                            GLES20.glDeleteBuffers(1, IntBuffer.wrap(intArrayOf(textureId)))
                            textureId = 0
                        }

                        val textureIds = intArrayOf(0)
                        GLES20.glGenTextures(1, IntBuffer.wrap(textureIds))
                        textureId = textureIds[0]
                        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
                        GLES20.glTexParameteri(
                            GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE
                        )
                        GLES20.glTexParameteri(
                            GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE
                        )
                        GLES20.glTexParameteri(
                            GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST
                        )
                        GLES20.glTexParameteri(
                            GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST
                        )
                        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
                        GLES20.glTexImage2D(
                            GLES20.GL_TEXTURE_2D,
                            0,
                            GLES20.GL_RGBA,
                            textureWidth,
                            textureHeight,
                            0,
                            GLES20.GL_RGBA,
                            GLES20.GL_UNSIGNED_BYTE,
                            null
                        )
                    }

                    // Update texture content
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
                    imageBuffer.position(0)
                    GLES20.glTexSubImage2D(
                        GLES20.GL_TEXTURE_2D, 0, 0, 0, textureWidth, textureHeight,

                        GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, imageBuffer
                    )
                }
            }

            // Set viewport size
            GLES20.glUniform2f(
                glUniformViewportSize, viewportWidth.toFloat(), viewportHeight.toFloat()
            )

            if (pointerVisible.get() && imageCrc32 != 0) {
                // TODO
                for (i in 0..3) {
                    // TL - TR - BL - BR
                    var x = pointerX.toFloat()
                    var y = pointerY.toFloat()
                    var u = 0.0f
                    var v = 0.0f

                    if (i == 1 || i == 3) {
                        // Right
                        x += textureWidth
                        u += 1.0f
                    }
                    if (i == 2 || i == 3) {
                        // Bottom
                        y += textureHeight
                        v += 1.0f
                    }

                    vertexBuffer.put(floatArrayOf(x, y, u, v), i * 4, 4)
                }

                GLES20.glUseProgram(glProgram)
                GLES20.glEnableVertexAttribArray(glAttribVertex)
                GLES20.glVertexAttribPointer(
                    glAttribVertex, 4, GLES20.GL_FLOAT, false, 0, vertexBuffer
                )
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            }

            val display = EGL14.eglGetCurrentDisplay()
            EGL14.eglSwapInterval(display, 0)
            EGLExt.eglPresentationTimeANDROID(
                display, EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW), System.nanoTime()
            )
        }
    }

    fun setImage() {
        requestRender()
    }

    fun setPosition() {
        requestRender()
    }

    private val renderer = Renderer()

    init {
        super.setEGLContextClientVersion(2)
        super.setZOrderMediaOverlay(true)
        super.setEGLConfigChooser(8, 8, 8, 8, 0, 0)
        super.setRenderer(renderer)
        super.getHolder().setFormat(PixelFormat.TRANSPARENT)
        super.setRenderMode(RENDERMODE_WHEN_DIRTY)
    }
}