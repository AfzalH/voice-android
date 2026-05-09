package com.srizonvoice.android.audio

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Builds a 44-byte little-endian WAV header + raw PCM payload, matching the
 * macOS `buildWAVFile` (Services.swift:213-239). The Groq Whisper endpoint
 * accepts this verbatim — no resampling needed because we capture at 16 kHz.
 */
object WavEncoder {

    private const val SAMPLE_RATE: Int = 16_000
    private const val CHANNELS: Short = 1
    private const val BITS_PER_SAMPLE: Short = 16

    fun encode(pcm: ByteArray): ByteArray {
        val byteRate = SAMPLE_RATE * CHANNELS * BITS_PER_SAMPLE / 8
        val blockAlign: Short = (CHANNELS * BITS_PER_SAMPLE / 8).toShort()
        val dataSize = pcm.size
        val chunkSize = 36 + dataSize

        val out = ByteArrayOutputStream(44 + dataSize)
        out.write("RIFF".toByteArray())
        out.write(int32(chunkSize))
        out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray())
        out.write(int32(16))           // subchunk1 size
        out.write(int16(1))            // PCM format
        out.write(int16(CHANNELS.toInt()))
        out.write(int32(SAMPLE_RATE))
        out.write(int32(byteRate))
        out.write(int16(blockAlign.toInt()))
        out.write(int16(BITS_PER_SAMPLE.toInt()))
        out.write("data".toByteArray())
        out.write(int32(dataSize))
        out.write(pcm)
        return out.toByteArray()
    }

    private fun int32(value: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()

    private fun int16(value: Int): ByteArray =
        ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort()).array()
}
