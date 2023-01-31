package moe.reimu.vdclient.packets

import java.nio.ByteBuffer

class VideoPacket(inData: ByteBuffer) : Packet {
    companion object {
        const val ident = 0
    }

    val pts: Long
    val data: ByteBuffer = ByteBuffer.allocate(inData.limit() - 8)

    init {
        pts = inData.long
        data.put(inData)
        data.rewind()
    }
}