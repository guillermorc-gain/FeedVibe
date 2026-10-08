package com.feedvibe.app.radio

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile

/** Escritor de bits (de más a menos significativo), para las tramas FLAC. */
private class BitWriter(initial: Int = 16384) {
    private val out = ByteArrayOutputStream(initial)
    private var acc = 0L
    private var bits = 0

    fun write(value: Long, n: Int) {
        if (n == 0) return
        var remaining = n
        while (remaining > 0) {
            val take = minOf(remaining, 32)
            val shift = remaining - take
            val chunk = (value ushr shift) and ((1L shl take) - 1)
            acc = (acc shl take) or chunk
            bits += take
            while (bits >= 8) {
                bits -= 8
                out.write(((acc ushr bits) and 0xFF).toInt())
            }
            acc = acc and ((1L shl bits) - 1)
            remaining -= take
        }
    }

    fun write(value: Int, n: Int) = write(value.toLong() and 0xFFFFFFFFL, n)

    /** n ceros seguidos de un uno (código unario). */
    fun unary(n: Int) {
        var z = n
        while (z >= 32) { write(0, 32); z -= 32 }
        write(1, z + 1)
    }

    fun alignToByte() { if (bits > 0) write(0, 8 - bits) }

    fun toByteArray(): ByteArray = out.toByteArray()
}

private val CRC8 = IntArray(256).also { t ->
    for (i in 0 until 256) {
        var c = i
        repeat(8) { c = if (c and 0x80 != 0) ((c shl 1) xor 0x07) and 0xFF else (c shl 1) and 0xFF }
        t[i] = c
    }
}
private val CRC16 = IntArray(256).also { t ->
    for (i in 0 until 256) {
        var c = i shl 8
        repeat(8) { c = if (c and 0x8000 != 0) ((c shl 1) xor 0x8005) and 0xFFFF else (c shl 1) and 0xFFFF }
        t[i] = c
    }
}

private fun crc8(b: ByteArray, len: Int): Int { var c = 0; for (i in 0 until len) c = CRC8[(c xor (b[i].toInt() and 0xFF)) and 0xFF]; return c }
private fun crc16(b: ByteArray, len: Int): Int { var c = 0; for (i in 0 until len) c = ((c shl 8) xor CRC16[((c ushr 8) xor (b[i].toInt() and 0xFF)) and 0xFF]) and 0xFFFF; return c }

/** Etiquetas del archivo: emisora, programa y logo. */
class AudioTags(
    val title: String,
    val artist: String,
    val album: String,
    val date: String,
    val comment: String,
    val picture: ByteArray?,
    val pictureMime: String = "image/jpeg",
)

/**
 * Codificador FLAC sin pérdidas (16 bits, 1 o 2 canales) con predictores fijos y Rice.
 * Escribe en un archivo y al cerrar completa la cabecera (número de muestras y tamaños).
 */
class FlacEncoder(file: File, private val sampleRate: Int, channels: Int, private val tags: AudioTags) {
    private val channels = channels.coerceIn(1, 2)
    private val raf = RandomAccessFile(file, "rw").apply { setLength(0) }
    private val block = Array(this.channels) { IntArray(BLOCK) }
    private var fill = 0
    private var frameNumber = 0L
    private var totalSamples = 0L
    private var minFrame = Int.MAX_VALUE
    private var maxFrame = 0

    init {
        raf.write("fLaC".toByteArray())
        // STREAMINFO (se reescribe al final con los datos reales).
        raf.write(byteArrayOf(0, 0, 0, 34))
        raf.write(ByteArray(34))
        writeVorbisComment(last = tags.picture == null)
        tags.picture?.let { writePicture(it) }
    }

    /** Muestras intercaladas (L, R, L, R…) de 16 bits; [frames] = muestras por canal. */
    fun write(pcm: ShortArray, frames: Int, srcChannels: Int) {
        for (i in 0 until frames) {
            for (c in 0 until channels) {
                block[c][fill] = pcm[i * srcChannels + minOf(c, srcChannels - 1)].toInt()
            }
            fill++
            if (fill == BLOCK) flushBlock()
        }
    }

    fun close() {
        if (fill > 0) flushBlock()
        // STREAMINFO definitivo.
        val w = BitWriter(64)
        w.write(BLOCK, 16)
        w.write(BLOCK, 16)
        w.write(if (minFrame == Int.MAX_VALUE) 0 else minFrame, 24)
        w.write(maxFrame, 24)
        w.write(sampleRate, 20)
        w.write(channels - 1, 3)
        w.write(15, 5)
        w.write(totalSamples, 36)
        w.write(0L, 64); w.write(0L, 64) // MD5 desconocido (permitido)
        raf.seek(8)
        raf.write(w.toByteArray())
        raf.close()
    }

    private fun writeVorbisComment(last: Boolean) {
        val fields = listOf(
            "TITLE=${tags.title}", "ARTIST=${tags.artist}", "ALBUM=${tags.album}",
            "DATE=${tags.date}", "GENRE=Radio", "COMMENT=${tags.comment}",
        ).map { it.toByteArray(Charsets.UTF_8) }
        val vendor = "FeedVibe".toByteArray()
        val body = ByteArrayOutputStream()
        fun le32(v: Int) { body.write(v and 0xFF); body.write((v ushr 8) and 0xFF); body.write((v ushr 16) and 0xFF); body.write((v ushr 24) and 0xFF) }
        le32(vendor.size); body.write(vendor)
        le32(fields.size)
        fields.forEach { le32(it.size); body.write(it) }
        blockHeader(4, body.size(), last)
        raf.write(body.toByteArray())
    }

    private fun writePicture(data: ByteArray) {
        val mime = tags.pictureMime.toByteArray()
        val desc = "Logo".toByteArray()
        val body = ByteArrayOutputStream()
        fun be32(v: Int) { body.write((v ushr 24) and 0xFF); body.write((v ushr 16) and 0xFF); body.write((v ushr 8) and 0xFF); body.write(v and 0xFF) }
        be32(3) // portada
        be32(mime.size); body.write(mime)
        be32(desc.size); body.write(desc)
        be32(0); be32(0); be32(0); be32(0) // ancho, alto, profundidad, colores: desconocidos
        be32(data.size); body.write(data)
        blockHeader(6, body.size(), last = true)
        raf.write(body.toByteArray())
    }

    private fun blockHeader(type: Int, length: Int, last: Boolean) {
        raf.write(byteArrayOf(((if (last) 0x80 else 0) or type).toByte(), (length ushr 16).toByte(), (length ushr 8).toByte(), length.toByte()))
    }

    private fun flushBlock() {
        val n = fill
        val w = BitWriter(n * channels * 2 + 64)
        // Cabecera de trama.
        w.write(0xFFF8, 16)
        val sizeCode = if (n == BLOCK) 12 else 7
        w.write(sizeCode, 4)
        w.write(0, 4) // frecuencia: la de STREAMINFO
        w.write(channels - 1, 4) // canales independientes
        w.write(4, 3) // 16 bits
        w.write(0, 1)
        writeUtf8(w, frameNumber)
        if (sizeCode == 7) w.write(n - 1, 16)
        val head = w.toByteArray()
        w.write(crc8(head, head.size), 8)
        for (c in 0 until channels) subframe(w, block[c], n)
        w.alignToByte()
        val frame = w.toByteArray()
        val withCrc = frame + byteArrayOf((crc16(frame, frame.size) ushr 8).toByte(), crc16(frame, frame.size).toByte())
        raf.write(withCrc)
        minFrame = minOf(minFrame, withCrc.size)
        maxFrame = maxOf(maxFrame, withCrc.size)
        totalSamples += n
        frameNumber++
        fill = 0
    }

    private fun writeUtf8(w: BitWriter, v: Long) {
        when {
            v < 0x80 -> w.write(v, 8)
            v < 0x800 -> { w.write(0xC0L or (v shr 6), 8); w.write(0x80L or (v and 0x3F), 8) }
            v < 0x10000 -> { w.write(0xE0L or (v shr 12), 8); w.write(0x80L or ((v shr 6) and 0x3F), 8); w.write(0x80L or (v and 0x3F), 8) }
            v < 0x200000 -> { w.write(0xF0L or (v shr 18), 8); for (s in intArrayOf(12, 6, 0)) w.write(0x80L or ((v shr s) and 0x3F), 8) }
            v < 0x4000000 -> { w.write(0xF8L or (v shr 24), 8); for (s in intArrayOf(18, 12, 6, 0)) w.write(0x80L or ((v shr s) and 0x3F), 8) }
            else -> { w.write(0xFCL or (v shr 30), 8); for (s in intArrayOf(24, 18, 12, 6, 0)) w.write(0x80L or ((v shr s) and 0x3F), 8) }
        }
    }

    /** Subtrama con el predictor fijo (orden 0 a 4) que deja los residuos más pequeños. */
    private fun subframe(w: BitWriter, x: IntArray, n: Int) {
        var bestOrder = 0
        var bestSum = Long.MAX_VALUE
        for (order in 0..minOf(4, n - 1)) {
            var sum = 0L
            for (i in order until n) sum += kotlin.math.abs(residual(x, i, order).toLong())
            if (sum < bestSum) { bestSum = sum; bestOrder = order }
        }
        val order = bestOrder
        w.write(0, 1)
        w.write(0b001000 or order, 6)
        w.write(0, 1)
        for (i in 0 until order) w.write(x[i] and 0xFFFF, 16)
        val res = IntArray(n - order) { residual(x, it + order, order) }
        // Partición: tantas como permita el tamaño (hasta 16) para ajustar mejor el parámetro.
        var p = 4
        while (p > 0 && (n % (1 shl p) != 0 || (n shr p) <= order)) p--
        w.write(0, 2) // Rice con parámetro de 4 bits
        w.write(p, 4)
        val parts = 1 shl p
        val per = n shr p
        var idx = 0
        for (part in 0 until parts) {
            val count = if (part == 0) per - order else per
            var sum = 0L
            for (j in 0 until count) sum += zigzag(res[idx + j])
            val mean = if (count > 0) sum / count else 0
            var k = 0
            while (k < 14 && (1L shl (k + 1)) <= mean) k++
            w.write(k, 4)
            for (j in 0 until count) {
                val u = zigzag(res[idx + j])
                w.unary((u ushr k).toInt())
                if (k > 0) w.write(u and ((1L shl k) - 1), k)
            }
            idx += count
        }
    }

    private fun residual(x: IntArray, i: Int, order: Int): Int = when (order) {
        0 -> x[i]
        1 -> x[i] - x[i - 1]
        2 -> x[i] - 2 * x[i - 1] + x[i - 2]
        3 -> x[i] - 3 * x[i - 1] + 3 * x[i - 2] - x[i - 3]
        else -> x[i] - 4 * x[i - 1] + 6 * x[i - 2] - 4 * x[i - 3] + x[i - 4]
    }

    private fun zigzag(r: Int): Long = ((r.toLong() shl 1) xor (r.toLong() shr 63)) and 0xFFFFFFFFFL

    companion object {
        private const val BLOCK = 4096
    }
}

/** WAV sin comprimir (16 bits). */
class WavWriter(file: File, private val sampleRate: Int, channels: Int) {
    private val channels = channels.coerceIn(1, 2)
    private val raf = RandomAccessFile(file, "rw").apply { setLength(0); write(ByteArray(44)) }
    private var dataBytes = 0L

    fun write(pcm: ShortArray, frames: Int, srcChannels: Int) {
        val buf = ByteArray(frames * channels * 2)
        var o = 0
        for (i in 0 until frames) for (c in 0 until channels) {
            val s = pcm[i * srcChannels + minOf(c, srcChannels - 1)].toInt()
            buf[o++] = s.toByte(); buf[o++] = (s shr 8).toByte()
        }
        raf.write(buf)
        dataBytes += buf.size
    }

    fun close() {
        val h = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt((36 + dataBytes).toInt()).put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(channels.toShort()).putInt(sampleRate)
        h.putInt(sampleRate * channels * 2).putShort((channels * 2).toShort()).putShort(16)
        h.put("data".toByteArray()).putInt(dataBytes.toInt())
        raf.seek(0)
        raf.write(h.array())
        raf.close()
    }
}
