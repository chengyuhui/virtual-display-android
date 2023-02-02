package moe.reimu.vdclient

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
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
import java.util.concurrent.locks.Condition
import java.util.concurrent.locks.Lock
import java.util.concurrent.locks.ReentrantLock
import kotlin.collections.ArrayDeque

class MainActivity : AppCompatActivity(), SurfaceHolder.Callback, TcpTransport.TransportCallback {
    private var transport: TcpTransport? = null

    private var surfaceReady = false

    private lateinit var binding: ActivityMainBinding

    private var codecData: CodecDataPacket? = null

    private var outputSurface: Surface? = null
    private val decoder = VideoDecoder()

    private var streamStartNanoTime: Long = 0

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
                val ptsUs = TimeUnit.NANOSECONDS.toMicros(streamStartNanoTime) + TimeUnit.MILLISECONDS.toMicros(packet.pts)
                decoder.processFrame(packet.data, ptsUs)
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

    private fun configureCodec() {
        decoder.configure(
            "video/avc",
            1920,
            1080,
            outputSurface ?: return,
            codecData?.data ?: return
        )
        decoder.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        decoder.close()
    }
}