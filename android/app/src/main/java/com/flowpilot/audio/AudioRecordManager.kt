package com.flowpilot.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.*
import java.io.*
import kotlin.math.log10
import kotlin.math.sqrt

data class VoiceMetrics(
    val audioSource: String = "VOICE_RECOGNITION",
    val sampleRate: Int = 16000,
    val sampleCount: Long = 0L,
    val nonZeroSamples: Long = 0L,
    val minSample: Short = 0,
    val maxSample: Short = 0,
    val averageAmplitude: Double = 0.0,
    val peakAmplitude: Int = 0,
    val rmsEnergy: Double = 0.0,
    val durationSeconds: Double = 0.0,
    val wavFileSize: Long = 0L
)

/**
 * AudioRecordManager provides reliable direct microphone audio recording on Android.
 * Records 16-bit PCM at 16kHz Mono and encodes to standard RIFF WAV format.
 * Works reliably on all emulators and physical devices without relying on Google Speech Services.
 */
class AudioRecordManager(private val context: Context) {

    companion object {
        private const val TAG = "AudioRecordManager"
        const val SAMPLE_RATE = 16000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private var audioRecord: AudioRecord? = null
    var isRecording = false
        private set
    private var recordingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var pcmFile: File? = null
    private var wavFile: File? = null
    var recordingStartTime: Long = 0L
        private set
    var recordedDurationMs: Long = 0L
        private set
    var recordedBytesCount: Long = 0L
        private set
    var lastMetrics: VoiceMetrics? = null
        private set
    private var currentAudioSourceName: String = "VOICE_RECOGNITION"
    private var totalSessionSamples: Long = 0L
    private var totalSessionNonZero: Long = 0L
    private var sessionMinSample: Short = 0
    private var sessionMaxSample: Short = 0
    private var sessionSumSquares: Double = 0.0
    private var sessionSumAbs: Double = 0.0

    @SuppressLint("MissingPermission")
    fun startRecording(
        audioSource: Int = MediaRecorder.AudioSource.VOICE_RECOGNITION,
        onRmsUpdate: ((Float) -> Unit)? = null,
        onSilenceDetected: (() -> Unit)? = null,
        maxDurationMs: Long = 8000L,
        onMaxDurationReached: (() -> Unit)? = null,
        onChunkAvailable: ((File) -> Unit)? = null
    ): Boolean {
        if (isRecording) {
            Log.w(TAG, "Already recording")
            return true
        }

        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (bufferSize <= 0) {
            Log.e(TAG, "Invalid buffer size: $bufferSize")
            return false
        }

        try {
            var chosenSource = audioSource
            var ar: AudioRecord? = null
            try {
                ar = AudioRecord(
                    chosenSource,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    bufferSize * 2
                )
                if (ar.state != AudioRecord.STATE_INITIALIZED) {
                    ar.release()
                    ar = null
                }
            } catch (e: Exception) {
                ar = null
            }

            if (ar == null && chosenSource != MediaRecorder.AudioSource.MIC) {
                Log.w(TAG, "[VOICE] AudioRecord with source $chosenSource failed; falling back to MIC")
                chosenSource = MediaRecorder.AudioSource.MIC
                try {
                    ar = AudioRecord(
                        chosenSource,
                        SAMPLE_RATE,
                        CHANNEL_CONFIG,
                        AUDIO_FORMAT,
                        bufferSize * 2
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "[VOICE] Fallback AudioRecord creation failed", e)
                    ar = null
                }
            }

            if (ar == null || ar.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord initialization failed")
                ar?.release()
                audioRecord = null
                return false
            }

            audioRecord = ar
            currentAudioSourceName = if (chosenSource == MediaRecorder.AudioSource.VOICE_RECOGNITION) "VOICE_RECOGNITION" else "MIC"

            audioRecord?.startRecording()
            isRecording = true
            recordingStartTime = System.currentTimeMillis()
            recordedDurationMs = 0L
            recordedBytesCount = 0L
            totalSessionSamples = 0L
            totalSessionNonZero = 0L
            sessionMinSample = 0
            sessionMaxSample = 0
            sessionSumSquares = 0.0
            sessionSumAbs = 0.0

            Log.i(TAG, "[VOICE] Recording started")
            Log.i(TAG, "[VOICE] audioSource=$currentAudioSourceName")
            Log.i(TAG, "[VOICE] sampleRate=$SAMPLE_RATE")
            Log.i(TAG, "[VOICE] channels=MONO")
            Log.i(TAG, "[VOICE] encoding=PCM_16BIT")
            Log.i(TAG, "[VOICE] bufferSize=${bufferSize * 2}")

            pcmFile = File(context.cacheDir, "audio_record_temp.pcm")
            wavFile = File(context.cacheDir, "voice_command.wav")

            recordingJob = scope.launch {
                val data = ShortArray(bufferSize)
                var outputStream: FileOutputStream? = null
                val startTime = System.currentTimeMillis()
                var speechDetected = false
                var silenceStartTime = 0L
                var lastChunkTime = System.currentTimeMillis()

                try {
                    outputStream = FileOutputStream(pcmFile)

                    while (isActive && isRecording) {
                        val readCount = audioRecord?.read(data, 0, data.size) ?: 0
                        if (readCount > 0) {
                            var chunkSumSquares = 0.0
                            var chunkPeak = 0
                            for (i in 0 until readCount) {
                                val s = data[i]
                                val sample = s.toInt()
                                // Write 16-bit PCM Little Endian
                                outputStream.write(sample and 0xff)
                                outputStream.write((sample shr 8) and 0xff)

                                val absVal = kotlin.math.abs(sample)
                                totalSessionSamples++
                                if (s != 0.toShort()) totalSessionNonZero++
                                if (s < sessionMinSample) sessionMinSample = s
                                if (s > sessionMaxSample) sessionMaxSample = s
                                sessionSumAbs += absVal
                                sessionSumSquares += (sample.toDouble() * sample.toDouble())

                                chunkSumSquares += (sample.toDouble() * sample.toDouble())
                                if (absVal > chunkPeak) chunkPeak = absVal
                            }
                            recordedBytesCount += (readCount * 2)

                            val rms = sqrt(chunkSumSquares / readCount)
                            // Convert RMS to dB: 20 * log10(rms / reference)
                            val rmsDb = if (rms > 1.0) (20 * log10(rms)).toFloat() else 0f
                            withContext(Dispatchers.Main) {
                                onRmsUpdate?.invoke(rmsDb)
                            }

                            // Phase 9: Ignore first 400ms warm-up to prevent initial button click/tap transient false trigger
                            val elapsed = System.currentTimeMillis() - startTime
                            if (elapsed > 400L) {
                                if (rmsDb > 20f || chunkPeak > 120) {
                                    speechDetected = true
                                    silenceStartTime = 0L
                                } else if (speechDetected) {
                                    if (silenceStartTime == 0L) {
                                        silenceStartTime = System.currentTimeMillis()
                                    } else if (System.currentTimeMillis() - silenceStartTime > 1600L) {
                                        // 1.6 seconds of silence after speech -> finish recording
                                        Log.i(TAG, "Silence detected after speech; finishing recording")
                                        withContext(Dispatchers.Main) {
                                            onSilenceDetected?.invoke()
                                        }
                                        break
                                    }
                                }
                            }

                            // Periodic chunk snapshot for live partial transcript (every ~1.5s after speech)
                            val now = System.currentTimeMillis()
                            if (speechDetected && onChunkAvailable != null && (now - lastChunkTime >= 1500L)) {
                                lastChunkTime = now
                                try {
                                    outputStream.flush()
                                    val chunkWav = createChunkSnapshot()
                                    if (chunkWav != null && chunkWav.length() > 44) {
                                        withContext(Dispatchers.Main) {
                                            onChunkAvailable.invoke(chunkWav)
                                        }
                                    }
                                } catch (e: Exception) {
                                    Log.d(TAG, "Chunk snapshot note: ${e.message}")
                                }
                            }
                        }

                        // Max duration check
                        if (System.currentTimeMillis() - startTime >= maxDurationMs) {
                            Log.i(TAG, "Max recording duration reached")
                            withContext(Dispatchers.Main) {
                                onMaxDurationReached?.invoke()
                            }
                            break
                        }
                    }
                } catch (e: CancellationException) {
                    // Normal cancellation on stop
                } catch (e: Exception) {
                    Log.e(TAG, "Recording stream error: ${e.message}", e)
                } finally {
                    try {
                        outputStream?.flush()
                        outputStream?.close()
                    } catch (ignored: Exception) {}
                }
            }

            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start AudioRecord: ${e.message}", e)
            audioRecord?.release()
            audioRecord = null
            isRecording = false
            return false
        }
    }

    private fun createChunkSnapshot(): File? {
        val pcm = pcmFile ?: return null
        if (!pcm.exists() || pcm.length() <= 3200) return null

        val chunkWav = File(context.cacheDir, "voice_chunk_partial.wav")
        val tempPcm = File(context.cacheDir, "voice_chunk_temp.pcm")
        try {
            pcm.copyTo(tempPcm, overwrite = true)
            convertPcmToWav(tempPcm, chunkWav, SAMPLE_RATE, 1, 16)
            tempPcm.delete()
            return chunkWav
        } catch (e: Exception) {
            return null
        }
    }

    fun stopRecording(): File? {
        recordedDurationMs = if (recordingStartTime > 0L) (System.currentTimeMillis() - recordingStartTime) else 0L

        if (!isRecording && wavFile?.exists() == true && (wavFile?.length() ?: 0) > 44) {
            return wavFile
        }

        isRecording = false
        recordingJob?.cancel()
        recordingJob = null

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping AudioRecord: ${e.message}")
        }
        audioRecord = null

        // Convert PCM to WAV
        val pcm = pcmFile
        val wav = wavFile
        if (pcm != null && pcm.exists() && wav != null) {
            try {
                convertPcmToWav(pcm, wav, SAMPLE_RATE, 1, 16)
                pcm.delete()
                val bytesLen = wav.length()
                recordedBytesCount = bytesLen

                val peakAmp = kotlin.math.max(kotlin.math.abs(sessionMinSample.toInt()), kotlin.math.abs(sessionMaxSample.toInt()))
                val rms = if (totalSessionSamples > 0) kotlin.math.sqrt(sessionSumSquares / totalSessionSamples) else 0.0
                val avgAmp = if (totalSessionSamples > 0) (sessionSumAbs / totalSessionSamples) else 0.0
                val durSec = recordedDurationMs / 1000.0

                lastMetrics = VoiceMetrics(
                    audioSource = currentAudioSourceName,
                    sampleRate = SAMPLE_RATE,
                    sampleCount = totalSessionSamples,
                    nonZeroSamples = totalSessionNonZero,
                    minSample = sessionMinSample,
                    maxSample = sessionMaxSample,
                    averageAmplitude = avgAmp,
                    peakAmplitude = peakAmp,
                    rmsEnergy = rms,
                    durationSeconds = durSec,
                    wavFileSize = bytesLen
                )

                Log.i(TAG, "[VOICE] Recording stopped")
                Log.i(TAG, "[VOICE] audioSource=$currentAudioSourceName")
                Log.i(TAG, "[VOICE] sampleRate=$SAMPLE_RATE")
                Log.i(TAG, "[VOICE] channels=MONO")
                Log.i(TAG, "[VOICE] durationMs=$recordedDurationMs")
                Log.i(TAG, "[VOICE] bytes=$bytesLen")
                Log.i(TAG, "[VOICE_METRICS] sampleCount=$totalSessionSamples")
                Log.i(TAG, "[VOICE_METRICS] nonZeroSamples=$totalSessionNonZero")
                Log.i(TAG, "[VOICE_METRICS] minSample=$sessionMinSample")
                Log.i(TAG, "[VOICE_METRICS] maxSample=$sessionMaxSample")
                Log.i(TAG, "[VOICE_METRICS] peakAmplitude=$peakAmp")
                Log.i(TAG, "[VOICE_METRICS] rms=${String.format(java.util.Locale.US, "%.2f", rms)}")
                Log.i(TAG, "[VOICE_METRICS] duration=${String.format(java.util.Locale.US, "%.2f", durSec)}s")
                Log.i(TAG, "[VOICE_METRICS] wavSize=${bytesLen}bytes")
                Log.i(TAG, "✅ WAV file created: ${wav.absolutePath} ($bytesLen bytes)")
                return wav
            } catch (e: Exception) {
                Log.e(TAG, "Failed to convert PCM to WAV: ${e.message}", e)
            }
        }

        return wavFile
    }

    private fun convertPcmToWav(
        pcmFile: File,
        wavFile: File,
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int
    ) {
        val pcmSize = pcmFile.length()
        val totalDataLen = pcmSize + 36
        val byteRate = sampleRate * channels * bitsPerSample / 8

        val header = ByteArray(44)
        // RIFF/WAVE header
        header[0] = 'R'.code.toByte()
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()
        header[4] = (totalDataLen and 0xff).toByte()
        header[5] = ((totalDataLen shr 8) and 0xff).toByte()
        header[6] = ((totalDataLen shr 16) and 0xff).toByte()
        header[7] = ((totalDataLen shr 24) and 0xff).toByte()
        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()
        // 'fmt ' chunk
        header[12] = 'f'.code.toByte()
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()
        header[16] = 16 // 4 bytes: size of 'fmt ' chunk
        header[17] = 0
        header[18] = 0
        header[19] = 0
        header[20] = 1 // format = 1 (PCM)
        header[21] = 0
        header[22] = channels.toByte()
        header[23] = 0
        header[24] = (sampleRate and 0xff).toByte()
        header[25] = ((sampleRate shr 8) and 0xff).toByte()
        header[26] = ((sampleRate shr 16) and 0xff).toByte()
        header[27] = ((sampleRate shr 24) and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte()
        header[29] = ((byteRate shr 8) and 0xff).toByte()
        header[30] = ((byteRate shr 16) and 0xff).toByte()
        header[31] = ((byteRate shr 24) and 0xff).toByte()
        header[32] = (channels * bitsPerSample / 8).toByte() // block align
        header[33] = 0
        header[34] = bitsPerSample.toByte() // bits per sample
        header[35] = 0
        // 'data' chunk
        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()
        header[40] = (pcmSize and 0xff).toByte()
        header[41] = ((pcmSize shr 8) and 0xff).toByte()
        header[42] = ((pcmSize shr 16) and 0xff).toByte()
        header[43] = ((pcmSize shr 24) and 0xff).toByte()

        val wavOut = FileOutputStream(wavFile)
        wavOut.write(header)

        val pcmIn = FileInputStream(pcmFile)
        val buffer = ByteArray(2048)
        var bytesRead: Int
        while (pcmIn.read(buffer).also { bytesRead = it } != -1) {
            wavOut.write(buffer, 0, bytesRead)
        }

        pcmIn.close()
        wavOut.flush()
        wavOut.close()
    }
}
