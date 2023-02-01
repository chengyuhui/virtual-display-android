package moe.reimu.vdclient.packets

import java.nio.ByteBuffer

class VideoPacket(val pts: Long, val data: ByteBuffer) : Packet {
    companion object {
        const val ident = 0
    }
}