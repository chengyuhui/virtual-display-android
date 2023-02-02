package moe.reimu.vdclient.packets

import java.nio.ByteBuffer

class CursorPositionPacket(inData: ByteBuffer) : Packet {
    companion object {
        const val ident = 4
    }

    val x: Int
    val y: Int
    val visible: Boolean

    init {
        x = inData.int
        y = inData.int
        visible = inData.int == 1
    }
}

class CursorImagePacket(inData: ByteBuffer): Packet {
    companion object {
        const val ident = 5
    }

    val crc32: Int
    val data: ByteBuffer = ByteBuffer.allocate(inData.limit() - 4)

    init {
        crc32 = inData.int
        data.put(inData)
    }
}