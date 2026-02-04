package com.anurag.cctvprimary

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import java.util.ArrayDeque
import kotlin.math.tanh

/**
 * Stream Audio Sender
 * 
 * Listener implementation that sends audio data to StreamServer for broadcasting to viewers.
 * This is registered with AudioSourceEngine when streaming audio is enabled.
 * 
 * Applies volume boost, quality improvements, and AAC compression before transmission.
 * Reduces bandwidth from ~768 kbps (raw PCM) to ~64 kbps (AAC) for better network performance.
 */
class StreamAudioSender(
    private val streamServer: StreamServer
) : AudioSourceEngine.AudioListener {
    
    private val logFrom = "StreamAudioSender"
    
    // Volume boost factor (1.0 = no boost, 2.0 = double volume, etc.)
    // Set to 1.5 for moderate boost (6dB gain) - can be adjusted
    private val volumeGain = 1.5f
    
    // Soft limiter threshold (0.0 to 1.0) - prevents harsh clipping
    private val limiterThreshold = 0.95f
    
    // AAC encoder settings
    private val audioSampleRate = 48000
    private val aacBitrate = 64_000 // 64 kbps for good quality at low bandwidth
    private var aacEncoder: MediaCodec? = null
    private val aacInputQueue = ArrayDeque<ByteArray>()
    private val aacInputLock = Any()
    private var encoderThread: Thread? = null
    @Volatile private var isEncoding = false
    
    // ADTS header for AAC (7 bytes)
    private var adtsHeader: ByteArray? = null
    private var adtsHeaderReady = false
    
    init {
        setupAacEncoder()
    }
    
    override fun onAudioData(pcm: ByteArray, sampleRate: Int, channels: Int) {
        try {
            // Lazy initialize encoder on first audio data
            if (!isEncoding && aacEncoder == null) {
                setupAacEncoder()
            }
            
            // If encoder failed to initialize, fallback to raw PCM
            if (!isEncoding || aacEncoder == null) {
                val processedPcm = applyGainWithSoftLimit(pcm)
                val tsUs = System.nanoTime() / 1000L
                streamServer.broadcastAudioDown(processedPcm, tsUs, sampleRate, channels, false)
                return
            }
            
            // Apply volume boost with soft limiting
            val processedPcm = applyGainWithSoftLimit(pcm)
            
            // Queue PCM data for AAC encoding
            synchronized(aacInputLock) {
                aacInputQueue.offer(processedPcm)
            }
        } catch (e: Exception) {
            Log.e(logFrom, "Error processing audio", e)
        }
    }
    
    /**
     * Apply gain (volume boost) with soft limiting to prevent clipping distortion.
     * Uses tanh-style soft limiting for smooth saturation.
     * 
     * @param input Raw PCM 16-bit audio data
     * @return Processed PCM data with gain applied
     */
    private fun applyGainWithSoftLimit(input: ByteArray): ByteArray {
        if (volumeGain <= 1.0f) {
            // No gain needed, return original
            return input
        }
        
        val output = ByteArray(input.size)
        var i = 0
        
        while (i + 1 < input.size) {
            // Read 16-bit PCM sample (little-endian)
            val low = input[i].toInt() and 0xFF
            val high = input[i + 1].toInt() and 0xFF
            var sample = (high shl 8) or low
            // Sign extend
            if (sample > 32767) sample -= 65536
            
            // Apply gain (use Float for precision)
            val amplified = sample.toFloat() * volumeGain
            
            // Soft limiting using tanh for smooth saturation (prevents harsh clipping)
            // Normalize to [-1, 1], apply tanh, scale back to int16 range
            val normalized = amplified.toDouble() / 32768.0
            val limited = tanh(normalized / limiterThreshold) * limiterThreshold
            var amplifiedInt = (limited * 32768.0).toInt()
            
            // Clamp to int16 range (hard limit as safety)
            amplifiedInt = amplifiedInt.coerceIn(-32768, 32767)
            
            // Write back (little-endian)
            output[i] = (amplifiedInt and 0xFF).toByte()
            output[i + 1] = ((amplifiedInt shr 8) and 0xFF).toByte()
            
            i += 2
        }
        
        return output
    }
    
    /**
     * Setup AAC encoder for network streaming
     */
    private fun setupAacEncoder() {
        try {
            val format = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC, audioSampleRate, 1
            ).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, aacBitrate)
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            }
            
            aacEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }
            
            isEncoding = true
            encoderThread = Thread({ encodingLoop() }, "StreamAudioSender-Encoder").apply {
                isDaemon = true
                start()
            }
            
            Log.d(logFrom, "AAC encoder configured: sampleRate=$audioSampleRate, bitrate=$aacBitrate")
        } catch (e: Exception) {
            Log.e(logFrom, "Failed to setup AAC encoder", e)
            // Fallback to raw PCM if encoder fails
            isEncoding = false
        }
    }
    
    /**
     * AAC encoding loop - processes queued PCM and sends encoded AAC frames
     */
    private fun encodingLoop() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
        
        val bufferInfo = MediaCodec.BufferInfo()
        val TIMEOUT_US = 10_000L
        
        while (isEncoding) {
            val encoder = aacEncoder ?: break
            
            try {
                // Feed PCM data to encoder
                while (true) {
                    val pcm: ByteArray? = synchronized(aacInputLock) {
                        aacInputQueue.poll()
                    }
                    
                    if (pcm == null) break
                    
                    val inputIndex = encoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inputIndex < 0) {
                        // No input buffer available, put PCM back
                        synchronized(aacInputLock) {
                            aacInputQueue.offerFirst(pcm)
                        }
                        break
                    }
                    
                    val inputBuffer = encoder.getInputBuffer(inputIndex) ?: continue
                    inputBuffer.clear()
                    inputBuffer.put(pcm)
                    
                    // Calculate PTS (use current time in microseconds)
                    val ptsUs = System.nanoTime() / 1_000L
                    encoder.queueInputBuffer(inputIndex, 0, pcm.size, ptsUs, 0)
                }
                
                // Drain encoded AAC frames
                while (true) {
                    val outputIndex = encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                    
                    when {
                        outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val format = encoder.outputFormat
                            // Build ADTS header from format info for AAC streaming
                            val formatSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            val formatChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            buildAdtsHeader(formatSampleRate, formatChannels)
                            adtsHeaderReady = true
                            Log.d(logFrom, "AAC format changed: sampleRate=$formatSampleRate, channels=$formatChannels, ADTS header ready")
                        }
                        outputIndex >= 0 -> {
                            if (bufferInfo.size > 0) {
                                val outputBuffer = encoder.getOutputBuffer(outputIndex)
                                if (outputBuffer != null && adtsHeaderReady) {
                                    outputBuffer.position(bufferInfo.offset)
                                    outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                                    
                                    // Create AAC frame with ADTS header
                                    val aacFrame = ByteArray(7 + bufferInfo.size) // ADTS header + payload
                                    if (adtsHeader != null) {
                                        // Update frame length in ADTS header
                                        val frameLength = aacFrame.size
                                        val header = adtsHeader!!.copyOf()
                                        header[3] = ((header[3].toInt() and 0xFC) or ((frameLength shr 11) and 0x03)).toByte()
                                        header[4] = ((frameLength shr 3) and 0xFF).toByte()
                                        header[5] = ((header[5].toInt() and 0x1F) or ((frameLength and 0x07) shl 5)).toByte()
                                        System.arraycopy(header, 0, aacFrame, 0, 7)
                                    }
                                    outputBuffer.get(aacFrame, 7, bufferInfo.size)
                                    
                                    // Send compressed AAC to StreamServer with PTS
                                    streamServer.broadcastAudioDown(aacFrame, bufferInfo.presentationTimeUs, audioSampleRate, 1, true)
                                }
                            }
                            encoder.releaseOutputBuffer(outputIndex, false)
                        }
                        outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> break
                        else -> break
                    }
                }
                
                Thread.sleep(5) // Small delay to prevent busy-waiting
            } catch (e: Exception) {
                Log.e(logFrom, "Error in encoding loop", e)
                break
            }
        }
        
        Log.d(logFrom, "AAC encoding loop exited")
    }
    
    /**
     * Build ADTS header for AAC streaming
     * @param sampleRate Sample rate in Hz
     * @param channels Number of channels (1 = mono, 2 = stereo)
     */
    private fun buildAdtsHeader(sampleRate: Int, channels: Int) {
        // ADTS header structure (7 bytes):
        // Sync word (12 bits) + MPEG version (1 bit) + Layer (2 bits) + Protection (1 bit)
        // + Profile (2 bits) + Sampling frequency index (4 bits) + Private (1 bit)
        // + Channel configuration (3 bits) + Originality (1 bit) + Home (1 bit)
        // + Copyright (1 bit) + Copyright start (1 bit) + Frame length (13 bits)
        // + Buffer fullness (11 bits) + Number of AAC frames (2 bits)
        
        // AAC LC (Low Complexity) profile.
        //
        // NOTE:
        // ADTS uses a 2-bit "profile" field:
        // - 0 = Main
        // - 1 = LC
        // - 2 = SSR
        // - 3 = reserved
        //
        // Our encoder is configured for AACObjectLC, so ADTS profile MUST be 1 (LC).
        // If we incorrectly emit profile=0 (Main), many decoders will fail to decode → silence on Viewer.
        val adtsProfileLc = 1
        val sampleRateIndex = when (sampleRate) {
            96000 -> 0
            88200 -> 1
            64000 -> 2
            48000 -> 3
            44100 -> 4
            32000 -> 5
            24000 -> 6
            22050 -> 7
            16000 -> 8
            12000 -> 9
            11025 -> 10
            8000 -> 11
            else -> 3 // Default to 48000
        }
        
        adtsHeader = ByteArray(7)
        adtsHeader!![0] = 0xFF.toByte() // Sync word high byte
        adtsHeader!![1] = 0xF1.toByte() // Sync word low byte + MPEG-4 + Layer 0 + No CRC
        // Byte 2:
        // profile (2 bits) + sampleRateIndex (4 bits) + private (1 bit) + channelCfg high (1 bit)
        adtsHeader!![2] =
            (((adtsProfileLc and 0x03) shl 6) or ((sampleRateIndex and 0x0F) shl 2) or ((channels shr 2) and 0x01)).toByte()
        // Byte 3:
        // channelCfg low (2 bits) + originality/home bits + frame length high (2 bits)
        // Frame length bits are filled dynamically per packet.
        adtsHeader!![3] = ((channels and 0x03) shl 6).toByte()
        adtsHeader!![4] = 0x00.toByte() // Frame length continues
        // Byte 5 contains frame length low bits (3) + buffer fullness high bits (5).
        // Use 0x7FF (all ones) for buffer fullness to indicate VBR (common for ADTS streaming).
        adtsHeader!![5] = 0x1F.toByte()
        // Byte 6 contains remaining buffer fullness bits + number_of_raw_data_blocks_in_frame (2 bits).
        // For VBR fullness: 0x7FF and 1 AAC block per frame => 0xFC.
        adtsHeader!![6] = 0xFC.toByte()
    }
    
    /**
     * Cleanup resources
     */
    fun release() {
        isEncoding = false
        try {
            encoderThread?.join(1000)
        } catch (e: InterruptedException) {
            Log.w(logFrom, "Interrupted while waiting for encoder thread", e)
        }
        
        try {
            aacEncoder?.stop()
            aacEncoder?.release()
        } catch (e: Exception) {
            Log.w(logFrom, "Error releasing AAC encoder", e)
        }
        aacEncoder = null
        
        synchronized(aacInputLock) {
            aacInputQueue.clear()
        }
    }
}
