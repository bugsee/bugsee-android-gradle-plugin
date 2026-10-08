package com.bugsee.android.gradle.integration.harness

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Minimal little-endian ELF64 reader: GNU build-id and presence of DWARF. */
internal object ElfInfo {

    private fun sections(f: File): Pair<ByteBuffer, List<Triple<String, Long, Long>>> {
        val bb = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        require(bb.getInt(0) == 0x464c457f) { "not an ELF file: $f" }
        val shoff = bb.getLong(0x28).toInt()
        val shentsize = bb.getShort(0x3A).toInt() and 0xffff
        val shnum = bb.getShort(0x3C).toInt() and 0xffff
        val shstrndx = bb.getShort(0x3E).toInt() and 0xffff
        fun hdr(i: Int) = shoff + i * shentsize
        val strOff = bb.getLong(hdr(shstrndx) + 0x18).toInt()
        fun name(off: Int): String {
            var e = strOff + off
            val sb = StringBuilder()
            while (bb.get(e).toInt() != 0) sb.append(bb.get(e++).toInt().toChar())
            return sb.toString()
        }
        val list = (0 until shnum).map { i ->
            Triple(name(bb.getInt(hdr(i))), bb.getLong(hdr(i) + 0x18), bb.getLong(hdr(i) + 0x20))
        }
        return bb to list
    }

    fun hasDwarf(f: File): Boolean = sections(f).second.any { it.first == ".debug_info" }

    fun buildId(f: File): String? {
        val (bb, secs) = sections(f)
        val (_, off, size) = secs.firstOrNull { it.first == ".note.gnu.build-id" } ?: return null
        val namesz = bb.getInt(off.toInt())
        val descsz = bb.getInt(off.toInt() + 4)
        val descStart = off.toInt() + 12 + ((namesz + 3) and 3.inv())
        check(descStart + descsz <= off + size + 12) { "truncated build-id note" }
        return (0 until descsz).joinToString("") { "%02x".format(bb.get(descStart + it)) }
    }
}
