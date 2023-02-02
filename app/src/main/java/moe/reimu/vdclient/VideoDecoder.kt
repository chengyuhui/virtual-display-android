package moe.reimu.vdclient

import android.media.MediaCodec
import android.media.MediaCodec.CodecException
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface
import moe.reimu.vdclient.packets.VideoPacket
import java.nio.ByteBuffer
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.math.max
import kotlin.math.min

class VideoDecoder {
    private val inputBufferQueueLock = ReentrantLock()
    private val inputBufferQueue = ArrayDeque<Int>()
    private val inputBufferCondition = inputBufferQueueLock.newCondition()

    private var running = false

    private val decoderLock = Object()
    private var decoder: MediaCodec? = null

    private var dropFrameThresholdMs: Int = 20
    private var lastEvaluationTimeNs: Long = 0
    private var totalFramesSinceEvaluation: Int = 0
    private var droppedFrameSinceEvaluation: Int = 0

    private var latencyListener: ((Int) -> Unit)? = null

    private val codecCallback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, bufferIndex: Int) {
            if (!running) {
                return
            }
            inputBufferQueueLock.lock()
            try {
                inputBufferQueue.add(bufferIndex)
                inputBufferCondition.signal()
            } finally {
                inputBufferQueueLock.unlock()
            }
        }

        override fun onOutputBufferAvailable(
            codec: MediaCodec, bufferIndex: Int, info: MediaCodec.BufferInfo
        ) {
            if (!running) {
                return
            }

            totalFramesSinceEvaluation += 1

            val now = System.nanoTime()
            val latency = now - info.presentationTimeUs * 1000
            try {
                if (latency > TimeUnit.MILLISECONDS.toNanos(dropFrameThresholdMs.toLong())) {
                    codec.releaseOutputBuffer(bufferIndex, false)
                    droppedFrameSinceEvaluation += 1
                } else {
                    codec.releaseOutputBuffer(bufferIndex, info.presentationTimeUs * 1000)
                }
            } catch (e: CodecException) {
                Log.e(TAG, "Failed to release output buffer", e)
            }

            if (now - lastEvaluationTimeNs > TimeUnit.SECONDS.toNanos(2)) {
                if (droppedFrameSinceEvaluation > totalFramesSinceEvaluation / 2) {
                    // More than 1/2 of the frames dropped
                    dropFrameThresholdMs = min(dropFrameThresholdMs + 5, 100)
                    Log.d(TAG, "New drop threshold: $dropFrameThresholdMs ms")
                    latencyListener?.invoke(dropFrameThresholdMs)
                }

                totalFramesSinceEvaluation = 0
                droppedFrameSinceEvaluation = 0
                lastEvaluationTimeNs = now
            }
        }

        override fun onError(p0: MediaCodec, p1: CodecException) {
            Log.e(TAG, "Media codec error", p1)
        }

        override fun onOutputFormatChanged(p0: MediaCodec, p1: MediaFormat) {
            Log.i(TAG, "onOutputFormatChanged")
        }
    }

    fun configure(
        mime: String, width: Int, height: Int, surface: Surface, codecData: List<ByteBuffer>
    ) {
        synchronized(decoderLock) {
            decoder?.release()

            var selectedCodecName: String? = null
            for (codec in MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos) {
                if (codec.isEncoder || !codec.supportedTypes.any { it.lowercase() == mime }) {
                    // Not H.264 decoder
                    continue
                }

                val caps = codec.getCapabilitiesForType(mime)
                val hw =
                    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) && codec.isHardwareAccelerated
                val ll =
                    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) && caps.isFeatureSupported(
                        MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency
                    )
                if (hw && ll) {
                    selectedCodecName = codec.name
                }
            }

            val decoder = if (selectedCodecName != null) {
                Log.i(TAG, "Using selected decoder $selectedCodecName")
                MediaCodec.createByCodecName(selectedCodecName)
            } else {
                Log.i(TAG, "Using system decoder")
                MediaCodec.createDecoderByType(mime)
            }
            decoder.setCallback(codecCallback)

            val format = MediaFormat.createVideoFormat(mime, width, height)
            for ((i, buf) in codecData.withIndex()) {
                format.setByteBuffer("csd-$i", buf)
            }
            if (Build.VERSION.SDK_INT >= 30) {
                format.setFeatureEnabled(MediaFormat.KEY_LOW_LATENCY, true)
            }
            format.setInteger(MediaFormat.KEY_OPERATING_RATE, 1000)
            format.setInteger(MediaFormat.KEY_PRIORITY, 0)

            decoder.configure(format, surface, null, 0)

            this.decoder = decoder
        }
    }

    fun setLatencyListener(cb: ((Int) -> Unit)?) {
        this.latencyListener = cb
    }

    fun processFrame(buffer: ByteBuffer, presentationTimeUs: Long) {
        if (!running) {
            return
        }

        inputBufferQueueLock.lock()
        try {
            var bufferIndex = inputBufferQueue.poll()
            if (bufferIndex == null) {
                val deadline = Date(System.currentTimeMillis() + 1000)
                while (bufferIndex == null) {
                    val stillWaiting = inputBufferCondition.awaitUntil(deadline)
                    if (!stillWaiting) {
                        // Deadline has elapsed
                        return
                    }
                    bufferIndex = inputBufferQueue.poll()
                }
            }

            synchronized(decoderLock) {
                val decoder = this.decoder ?: return

                // We have an available buffer
                val inputBuffer = decoder.getInputBuffer(bufferIndex)!!
                inputBuffer.position(0)
                inputBuffer.put(buffer)

                try {
                    decoder.queueInputBuffer(
                        bufferIndex, 0, inputBuffer.position(), presentationTimeUs, 0
                    )
                } catch (e: java.lang.IllegalStateException) {
                    Log.e(TAG, "Failed to queue input buffer", e)
                }
            }
        } finally {
            inputBufferQueueLock.unlock()
        }
    }

    fun start() {
        synchronized(decoderLock) {
            val decoder =
                this.decoder ?: throw java.lang.IllegalStateException("No decoder configured")
            decoder.start()
            running = true
        }
    }

    /**
     * Stops and resets the decoder.
     */
    fun stop() {
        inputBufferQueueLock.lock()
        try {
            synchronized(decoderLock) {
                decoder?.stop()
                decoder?.reset()
            }

            running = false

            inputBufferQueue.clear()
        } finally {
            inputBufferQueueLock.unlock()
        }
    }

    /**
     * Release any resources associated with the decoder.
     */
    fun close() {
        stop()
        synchronized(decoderLock) {
            this.decoder?.release()
            this.decoder = null
        }
    }
}