package moe.reimu.vdclient.packets

import java.nio.ByteBuffer

class TimestampPacket(inData: ByteBuffer) : Packet {
    companion object {
        const val ident = 2
    }

    val timestamp: Long

    init {
        timestamp = inData.long
    }
}