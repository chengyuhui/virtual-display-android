package moe.reimu.vdclient.packets

import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

class VideoPacket(val pts: Long, val data: ByteBuffer) : Packet {
    companion object {
        const val ident = 0
    }

    fun calculateLatencyMs(streamStartTimeNs: Long): Long {
        val now = System.nanoTime()
        val pts = streamStartTimeNs + TimeUnit.MILLISECONDS.toNanos(pts)
        return TimeUnit.NANOSECONDS.toMillis(now - pts)
    }
}