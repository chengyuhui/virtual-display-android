package moe.reimu.vdclient

import android.util.Log
import moe.reimu.vdclient.packets.*
import java.net.SocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.SocketChannel
import kotlin.concurrent.thread

class TcpTransport(val remoteAddr: SocketAddress, private val callback: TransportCallback) {
    private var readThread: ReadThread? = null
    private var channel: SocketChannel? = null

    fun start() {
        Log.i(TAG, "Starting")
        thread(start = true) {
            val ch = SocketChannel.open(remoteAddr)
            this.channel = ch

            readThread = ReadThread(ch, callback).apply {
                start()
            }
        }
    }

    fun stop() {
        Log.i(TAG, "Stopping")
        readThread?.stopRunning()
        thread(start = true) {
            channel?.close()
        }
    }

    interface TransportCallback {
        fun onPacket(packet: Packet)
    }
}

class ReadThread(var socket: SocketChannel, private val callback: TcpTransport.TransportCallback) :
    Thread() {
    companion object {
        const val DATA_BUFFER_MAX = 1024 * 1024 * 30
    }

    var running = true

    private val headerAndSizeBuffer = allocateBuffer(4 + 4)
    private val dataBuffer = allocateBuffer(DATA_BUFFER_MAX)

    override fun run() {
        while (running) {
            try {
                readPacket()
            } catch (e: java.lang.Exception) {
                Log.e(TAG, "Failed to read", e)
                running = false
            }
        }
    }

    private fun readPacket() {
        while (headerAndSizeBuffer.hasRemaining()) {
            socket.read(headerAndSizeBuffer)
        }
        headerAndSizeBuffer.flip()
        val type = headerAndSizeBuffer.int
        val len = headerAndSizeBuffer.int
        headerAndSizeBuffer.clear()

        if (len > DATA_BUFFER_MAX) {
            throw java.lang.Exception("Incoming packet is too large (> 30M)")
        }

        dataBuffer.limit(len)
        while (dataBuffer.hasRemaining()) {
            socket.read(dataBuffer)
        }
        dataBuffer.flip()

        val packet = when (type) {
            // 0
            VideoPacket.ident -> {
                VideoPacket(dataBuffer)
            }
            // 1
            AudioPacket.ident -> {
                AudioPacket(dataBuffer)
            }
            // 2
            TimestampPacket.ident -> {
                TimestampPacket(dataBuffer)
            }
            // 3
            CodecDataPacket.ident -> {
                Log.i(TAG, "CodecDataPacket")
                CodecDataPacket(dataBuffer)
            }
            else -> {
                Log.w(TAG, "Unrecognized packet type: $type")
                null
            }
        }

        if (packet != null) {
            callback.onPacket(packet)
        }

        dataBuffer.clear()
    }

    fun stopRunning() {
        running = false
    }
}

fun allocateBuffer(size: Int): ByteBuffer {
    return ByteBuffer.allocate(size).apply {
        order(ByteOrder.BIG_ENDIAN)
    }
}

/**
 * extension function to provide TAG value
 */
val Any.TAG: String
    get() {
        return if (!javaClass.isAnonymousClass) {
            val name = javaClass.simpleName
            if (name.length <= 23) name else name.substring(0, 23)// first 23 chars
        } else {
            val name = javaClass.name
            if (name.length <= 23) name else name.substring(
                name.length - 23,
                name.length
            )// last 23 chars
        }
    }