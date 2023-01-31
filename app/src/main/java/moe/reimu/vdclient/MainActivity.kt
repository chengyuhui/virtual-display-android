package moe.reimu.vdclient

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import android.view.SurfaceHolder
import android.os.Bundle
import android.provider.MediaStore.Audio.Media
import android.util.Log
import android.view.Surface
import moe.reimu.vdclient.databinding.ActivityMainBinding
import moe.reimu.vdclient.packets.CodecDataPacket
import moe.reimu.vdclient.packets.Packet
import moe.reimu.vdclient.packets.TimestampPacket
import moe.reimu.vdclient.packets.VideoPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.nio.ByteBuffer
import java.util.*
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.Lock
import java.util.concurrent.locks.ReentrantLock

class MainActivity : AppCompatActivity(), SurfaceHolder.Callback, TcpTransport.TransportCallback {
    private var transport: TcpTransport? = null

    private var surfaceReady = false

    private lateinit var binding: ActivityMainBinding

    private var codecData: CodecDataPacket? = null

    private var outputSurface: Surface? = null
    private var decoderLock = Object()
    private var decoder: MediaCodec? = null

    //    private val videoBuffersLock = Object()
    private val videoBuffers = ArrayBlockingQueue<VideoPacket>(30)

    private var streamStartNanoTime: Long? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.surfaceView.holder.addCallback(this)

        window.setSustainedPerformanceMode(true)

        transport = TcpTransport(InetSocketAddress("127.0.0.1", 9867), this)
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
    }

    override fun onPacket(packet: Packet) {
        when (packet) {
            is VideoPacket -> {
                videoBuffers.offer(packet, 100, TimeUnit.MILLISECONDS)
            }
            is CodecDataPacket -> {
                codecData = packet
                configureCodec()
            }
            is TimestampPacket -> {
                val currentNs = System.nanoTime()
                streamStartNanoTime = currentNs - TimeUnit.MILLISECONDS.toNanos(packet.timestamp)
            }
        }
    }

    fun configureCodec() {
        val callback = object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, bufferId: Int) {
                val inputBuffer = codec.getInputBuffer(bufferId)!!
                val packet = videoBuffers.poll(100, TimeUnit.MILLISECONDS) ?: return

                if (packet.data.remaining() > inputBuffer.remaining()) {
                    return
                }

                inputBuffer.put(packet.data)
                codec.queueInputBuffer(bufferId, 0, inputBuffer.position(), packet.pts * 1000, 0)
            }

            override fun onOutputBufferAvailable(
                codec: MediaCodec, bufferId: Int, info: MediaCodec.BufferInfo
            ) {
                if (streamStartNanoTime != null) {
                    val frameTimestamp = TimeUnit.MICROSECONDS.toNanos(info.presentationTimeUs)
                    val renderTimestamp = streamStartNanoTime!! + frameTimestamp
                    codec.releaseOutputBuffer(bufferId, renderTimestamp)
                } else {
                    codec.releaseOutputBuffer(bufferId, true)
                }
            }

            override fun onError(p0: MediaCodec, p1: MediaCodec.CodecException) {
                Log.e(TAG, "Codec exception", p1)
            }

            override fun onOutputFormatChanged(p0: MediaCodec, p1: MediaFormat) {
            }
        }

        synchronized(decoderLock) {
            decoder?.release()

            val codecData = codecData?.data ?: return
            val surface = outputSurface ?: return

            Log.i(TAG, "Configuring codec")

            val decoder = MediaCodec.createDecoderByType("video/avc")
            decoder.setCallback(callback)
            val format = MediaFormat.createVideoFormat("media/avc", 1920, 1080)
            for ((i, buf) in codecData.withIndex()) {
                format.setByteBuffer("csd-$i", buf)
            }
            if (Build.VERSION.SDK_INT >= 30) {
                format.setFeatureEnabled(MediaFormat.KEY_LOW_LATENCY, true)
            }
            if (Build.VERSION.SDK_INT >= 23) {
                format.setInteger(MediaFormat.KEY_OPERATING_RATE, 240)
            }
            decoder.configure(format, surface, null, 0)
            decoder.start()
        }
    }
}