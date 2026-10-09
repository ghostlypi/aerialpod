package org.aerialpod.core.ads

import java.io.File
import java.io.RandomAccessFile

/** A downloaded episode, read in place — never the whole file into memory. */
class FileByteSource(private val file: File) : ByteSource {
    override val size: Long get() = file.length()

    override fun read(offset: Long, length: Int): ByteArray =
        RandomAccessFile(file, "r").use { raf ->
            if (offset >= raf.length()) return ByteArray(0)
            raf.seek(offset)
            val n = minOf(length.toLong(), raf.length() - offset).toInt()
            ByteArray(n).also { raf.readFully(it) }
        }
}
