package moe.reimu.vdclient.packets

import java.nio.ByteBuffer

class AudioPacket(inData: ByteBuffer): Packet {
    companion object {
        const val ident = 1
    }

    private val data: ByteBuffer = ByteBuffer.allocate(inData.limit())
    init {
        data.put(inData)
    }
}