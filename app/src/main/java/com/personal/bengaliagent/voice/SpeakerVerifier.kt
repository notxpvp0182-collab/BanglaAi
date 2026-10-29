package com.personal.bengaliagent.voice

import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.PI
import kotlin.math.sqrt

/**
 * Local, on-device acoustic speaker feature extractor & cosine similarity matcher.
 *
 * IMPORTANT SECURITY REALITY:
 * This is a lightweight acoustic preference signal (16-D spectral/prosodic embedding)
 * to help pause automation when an obviously different voice speaks.
 * It is NOT a cryptographic or biometric lock and MUST NEVER authorize sensitive actions.
 * Raw PCM audio is zeroed out immediately after computing the 16 floats.
 */
object SpeakerVerifier {

    const val EMBEDDING_DIM = 16
    const val DEFAULT_MATCH_THRESHOLD = 0.86f
    const val UNCERTAIN_LOWER_BOUND = 0.75f

    enum class MatchStatus {
        MATCHED,
        UNCERTAIN,
        DIFFERENT_SPEAKER,
        NOT_ENROLLED
    }

    data class VerificationOutcome(
        val status: MatchStatus,
        val similarityScore: Float,
        val warningMessageBn: String?
    )

    /**
     * Extracts a normalized 16-element float embedding from 16kHz 16-bit mono PCM samples.
     * Clears [pcmSamples] in-place if [zeroOutBuffer] is true.
     */
    fun extractEmbedding(
        pcmSamples: ShortArray,
        sampleRate: Int = 16000,
        zeroOutBuffer: Boolean = true
    ): FloatArray? {
        if (pcmSamples.size < sampleRate / 2) return null

        try {
            // Normalize and filter active voiced frames
            val frameSize = 512
            val hopSize = 256
            val numBands = 12
            val bandEnergies = DoubleArray(numBands)
            var activeFrames = 0
            var totalZcr = 0.0
            var totalCentroid = 0.0
            var totalRms = 0.0
            var pitchPeriodSum = 0.0
            var pitchFrames = 0

            val window = DoubleArray(frameSize) { i ->
                0.54 - 0.46 * cos(2.0 * PI * i / (frameSize - 1))
            }

            var offset = 0
            while (offset + frameSize <= pcmSamples.size) {
                var frameEnergy = 0.0
                var zcr = 0
                val windowed = DoubleArray(frameSize)
                for (i in 0 until frameSize) {
                    val s = pcmSamples[offset + i] / 32768.0
                    frameEnergy += s * s
                    windowed[i] = s * window[i]
                    if (i > 0) {
                        val prev = pcmSamples[offset + i - 1]
                        if ((pcmSamples[offset + i] >= 0 && prev < 0) ||
                            (pcmSamples[offset + i] < 0 && prev >= 0)
                        ) {
                            zcr++
                        }
                    }
                }
                val rms = sqrt(frameEnergy / frameSize)
                // Only process voiced/active speech frames above silence floor
                if (rms > 0.015) {
                    activeFrames++
                    totalRms += rms
                    totalZcr += zcr.toDouble() / frameSize

                    // Estimate fundamental pitch period via autocorrelation (80Hz - 400Hz)
                    val minLag = sampleRate / 400
                    val maxLag = (sampleRate / 80).coerceAtMost(frameSize / 2)
                    var bestLag = minLag
                    var maxCorr = 0.0
                    for (lag in minLag..maxLag) {
                        var corr = 0.0
                        for (i in 0 until frameSize - lag) {
                            corr += windowed[i] * windowed[i + lag]
                        }
                        if (corr > maxCorr) {
                            maxCorr = corr
                            bestLag = lag
                        }
                    }
                    if (maxCorr > frameEnergy * 0.25) {
                        pitchPeriodSum += (sampleRate.toDouble() / bestLag) / 400.0
                        pitchFrames++
                    }

                    // Compute 12 log-spaced spectral band energies via Goertzel/DFT bins
                    var weightedFreqSum = 0.0
                    var magSum = 1e-9
                    for (b in 0 until numBands) {
                        val bin = 2 + b * 6 // Covers ~60Hz to ~2.4kHz vocal formants
                        val omega = 2.0 * PI * bin / frameSize
                        val coeff = 2.0 * cos(omega)
                        var q1 = 0.0
                        var q2 = 0.0
                        for (i in 0 until frameSize) {
                            val q0 = windowed[i] + coeff * q1 - q2
                            q2 = q1
                            q1 = q0
                        }
                        val power = (q1 * q1 + q2 * q2 - q1 * q2 * coeff).coerceAtLeast(1e-9)
                        val logPower = ln(1.0 + power * 100.0)
                        bandEnergies[b] += logPower
                        weightedFreqSum += (b + 1) * logPower
                        magSum += logPower
                    }
                    totalCentroid += (weightedFreqSum / magSum) / numBands
                }
                offset += hopSize
            }

            if (activeFrames < 5) return null

            val rawFeature = FloatArray(EMBEDDING_DIM)
            for (b in 0 until numBands) {
                rawFeature[b] = (bandEnergies[b] / activeFrames).toFloat()
            }
            rawFeature[12] = (totalZcr / activeFrames).toFloat() * 5f
            rawFeature[13] = (totalCentroid / activeFrames).toFloat() * 3f
            rawFeature[14] = if (pitchFrames > 0) (pitchPeriodSum / pitchFrames).toFloat() * 4f else 0.4f
            rawFeature[15] = (totalRms / activeFrames).toFloat() * 2f

            return l2Normalize(rawFeature)
        } finally {
            if (zeroOutBuffer) {
                pcmSamples.fill(0)
            }
        }
    }

    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size || a.isEmpty()) return 0f
        var dot = 0f
        var normA = 0f
        var normB = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val denom = sqrt(normA) * sqrt(normB)
        return if (denom > 1e-6f) (dot / denom).coerceIn(-1f, 1f) else 0f
    }

    fun verifyAgainstEnrolled(
        enrolledEmbedding: FloatArray?,
        candidateEmbedding: FloatArray?
    ): VerificationOutcome {
        if (enrolledEmbedding == null || enrolledEmbedding.size != EMBEDDING_DIM) {
            return VerificationOutcome(MatchStatus.NOT_ENROLLED, 0f, null)
        }
        if (candidateEmbedding == null || candidateEmbedding.size != EMBEDDING_DIM) {
            return VerificationOutcome(
                MatchStatus.UNCERTAIN,
                0f,
                "আমি তোমার কণ্ঠ নিশ্চিত করতে পারছি না—দয়া করে নিজে নিশ্চিত করো।"
            )
        }
        val sim = cosineSimilarity(enrolledEmbedding, candidateEmbedding)
        return when {
            sim >= DEFAULT_MATCH_THRESHOLD -> VerificationOutcome(
                MatchStatus.MATCHED,
                sim,
                null
            )
            sim >= UNCERTAIN_LOWER_BOUND -> VerificationOutcome(
                MatchStatus.UNCERTAIN,
                sim,
                "আমি তোমার কণ্ঠ নিশ্চিত করতে পারছি না—দয়া করে নিজে নিশ্চিত করো।"
            )
            else -> VerificationOutcome(
                MatchStatus.DIFFERENT_SPEAKER,
                sim,
                "আমি তোমার কণ্ঠ নিশ্চিত করতে পারছি না—দয়া করে নিজে নিশ্চিত করো। (ভিন্ন কণ্ঠ শনাক্ত হয়েছে, সংবেদনশীল কাজ স্থগিত রাখা হয়েছে)"
            )
        }
    }

    fun serializeEmbedding(embedding: FloatArray): String =
        embedding.joinToString(",") { "%.5f".format(java.util.Locale.US, it) }

    fun deserializeEmbedding(serialized: String?): FloatArray? {
        if (serialized.isNullOrBlank()) return null
        val parts = serialized.split(",")
        if (parts.size != EMBEDDING_DIM) return null
        return try {
            FloatArray(EMBEDDING_DIM) { idx -> parts[idx].toFloat() }
        } catch (e: NumberFormatException) {
            null
        }
    }

    private fun l2Normalize(vec: FloatArray): FloatArray {
        var sumSq = 0f
        for (v in vec) sumSq += v * v
        val norm = sqrt(sumSq).coerceAtLeast(1e-6f)
        return FloatArray(vec.size) { i -> vec[i] / norm }
    }
}
