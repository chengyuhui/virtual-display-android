package moe.reimu.vdclient

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.appcompat.app.AppCompatActivity
import android.view.SurfaceHolder
import android.os.Bundle
import android.util.Log
import android.view.Choreographer
import android.view.Surface
import android.view.View
import androidx.core.view.doOnLayout
import moe.reimu.vdclient.databinding.ActivityMainBinding
import moe.reimu.vdclient.packets.*
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class MainActivity : AppCompatActivity(), SurfaceHolder.Callback, TcpTransport.TransportCallback {
    private var transport: TcpTransport? = null

    private var surfaceReady = false

    private lateinit var binding: ActivityMainBinding

    private var configuration: ConfigurePacket? = null

    private var outputSurface: Surface? = null
    private val decoder = VideoDecoder()

    private var streamStartNanoTime: Long = 0

    private var pointerX = AtomicInteger()
    private var pointerY = AtomicInteger()
    private var pointerVisible = AtomicBoolean()

    private val pointerImageLock = Object()
    private val pointerImageCache = mutableMapOf<Int, Bitmap>()
    private var pointerImageCrc32 = 0
    private var pointerImageUpdated = false

    private var videoOriginalWidth = 0.0f
    private var videoOriginalHeight = 0.0f
    private var videoDisplayWidth = 0.0f
    private var videoDisplayHeight = 0.0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.surfaceView.holder.addCallback(this)

        window.setSustainedPerformanceMode(true)

        transport = TcpTransport(InetSocketAddress("127.0.0.1", 9867), this)

        decoder.setLatencyListener {
            runOnUiThread {
                binding.latencyTextView.text = "Latency: $it ms"
            }
        }

        val choreographer = Choreographer.getInstance()
        val frameCallback = object : Choreographer.FrameCallback {
            override fun doFrame(p0: Long) {
                val view = binding.cursorImageView

                val newVisible = if (pointerVisible.get()) {
                    View.VISIBLE
                } else {
                    View.GONE
                }

                if (newVisible != view.visibility) {
                    view.visibility = newVisible
                }

                if (newVisible == View.VISIBLE && videoDisplayHeight != 0.0f && videoDisplayWidth != 0.0f) {
                    view.translationX =
                        (pointerX.get().toFloat() / videoOriginalWidth) * videoDisplayWidth
                    view.translationY =
                        (pointerY.get().toFloat() / videoOriginalHeight) * videoDisplayHeight
                }

                synchronized(pointerImageLock) {
                    if (pointerImageUpdated) {
                        view.setImageBitmap(pointerImageCache[pointerImageCrc32])
                        pointerImageUpdated = false
                    }
                }

                choreographer.postFrameCallback(this)
            }
        }
        choreographer.postFrameCallback(frameCallback)

        binding.surfaceView.doOnLayout {
            videoDisplayWidth = it.measuredWidth.toFloat()
            videoDisplayHeight = it.measuredHeight.toFloat()
        }
    }

    override fun onResume() {
        super.onResume()
        transport?.start()
    }

    override fun onPause() {
        super.onPause()
        transport?.stop()
    }

    override fun surfaceCreated(p0: SurfaceHolder) {
        Log.i(TAG, "Surface has been created")
        outputSurface = p0.surface
        configureCodec()
    }

    override fun surfaceChanged(p0: SurfaceHolder, p1: Int, p2: Int, p3: Int) {
    }

    override fun surfaceDestroyed(p0: SurfaceHolder) {
        Log.i(TAG, "Surface has been destroyed")
        outputSurface = null
        decoder.stop()
    }

    override fun onPacket(packet: Packet) {
        when (packet) {
            is VideoPacket -> {
                val ptsUs =
                    TimeUnit.NANOSECONDS.toMicros(streamStartNanoTime) + TimeUnit.MILLISECONDS.toMicros(
                        packet.pts
                    )
                decoder.processFrame(packet.data, ptsUs)
            }
            is ConfigurePacket -> {
                configuration = packet

                videoOriginalWidth = packet.width.toFloat()
                videoOriginalHeight = packet.height.toFloat()

                configureCodec()
            }
            is TimestampPacket -> {
                val currentNs = System.nanoTime()
                streamStartNanoTime = currentNs - TimeUnit.MILLISECONDS.toNanos(packet.timestamp)
            }
            is CursorPositionPacket -> {
                pointerX.set(packet.x)
                pointerY.set(packet.y)
                pointerVisible.set(packet.visible)
            }
            is CursorImagePacket -> {
                val crc32 = packet.crc32
                val pngBuffer = packet.data
                synchronized(pointerImageLock) {
                    if (pointerImageCache.count() > 60) {
                        pointerImageCache.clear()
                    }

                    if (!pointerImageCache.containsKey(crc32)) {
                        val bitmap = BitmapFactory.decodeByteArray(
                            pngBuffer.array(), pngBuffer.arrayOffset(), pngBuffer.limit()
                        )
                        pointerImageCache[crc32] = bitmap
                    }

                    pointerImageCrc32 = crc32
                    pointerImageUpdated = true
                }
            }
        }
    }

    private fun configureCodec() {
        val config = configuration ?: return
        decoder.configure(
            "video/avc", config.width, config.height, outputSurface ?: return, config.data
        )
        decoder.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        decoder.close()
    }
}