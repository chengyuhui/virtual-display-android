package moe.reimu.vdclient.packets

import java.nio.ByteBuffer

class ConfigurePacket(inData: ByteBuffer) : Packet {
    companion object {
        const val ident = 3
    }

    val width: Int
    val height: Int
    val data: List<ByteBuffer>

    init {
        width = inData.int
        height = inData.int

        val buffers = mutableListOf<ByteBuffer>()
        while (inData.hasRemaining()) {
            inData.limit(inData.position() + 4)
            val len = inData.int
            val buf = ByteBuffer.allocate(len)
            inData.limit(inData.position() + len)
            buf.put(inData)
            buffers.add(buf)
        }
        data = buffers
    }
}