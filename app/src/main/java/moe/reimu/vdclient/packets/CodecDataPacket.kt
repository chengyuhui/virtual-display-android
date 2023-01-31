package moe.reimu.vdclient.packets

import java.nio.ByteBuffer

class CodecDataPacket(inData: ByteBuffer) : Packet {
    companion object {
        const val ident = 3
    }

    val data: List<ByteBuffer>

    init {
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