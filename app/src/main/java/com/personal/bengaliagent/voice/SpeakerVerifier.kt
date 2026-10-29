package com.personal.bengaliagent.voice

import kotlin.math.cos
import kotlin.math.PI
import kotlin.math.sqrt

object SpeakerVerifier {

    const val EMBEDDING_DIM = 16
    const val DEFAULT_MATCH_THRESHOLD = 0.88f
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

    fun extractEmbedding(
        pcmSamples: ShortArray,
        sampleRate: Int = 16000,
        zeroOutBuffer: Boolean = true
    ): FloatArray? {
        if (pcmSamples.size < sampleRate / 2) return null

        try {
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
                if (rms > 0.015) {
                    activeFrames++
                    totalRms += rms
                    totalZcr += zcr.toDouble() / frameSize

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
                    if (maxCorr > frameEnergy * 0.20) {
                        pitchPeriodSum += (sampleRate.toDouble() / bestLag) / 400.0
                        pitchFrames++
                    }

                    var weightedFreqSum = 0.0
                    var magSum = 1e-9
                    val frameBandPower = DoubleArray(numBands)
                    for (b in 0 until numBands) {
                        val startBin = 3 + b * 4
                        var subBandSum = 0.0
                        for (bin in startBin until (startBin + 4)) {
                            val omega = 2.0 * PI * bin / frameSize
                            val coeff = 2.0 * cos(omega)
                            var q1 = 0.0
                            var q2 = 0.0
                            for (i in 0 until frameSize) {
                                val q0 = windowed[i] + coeff * q1 - q2
                                q2 = q1
                                q1 = q0
                            }
                            val power = (q1 * q1 + q2 * q2 - q1 * q2 * coeff).coerceAtLeast(0.0)
                            subBandSum += sqrt(power)
                        }
                        frameBandPower[b] = subBandSum
                        weightedFreqSum += (b + 1) * subBandSum
                        magSum += subBandSum
                    }
                    for (b in 0 until numBands) {
                        bandEnergies[b] += (frameBandPower[b] / magSum) * 4.0
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
            rawFeature[12] = (totalZcr / activeFrames).toFloat() * 3f
            rawFeature[13] = (totalCentroid / activeFrames).toFloat() * 2f
            rawFeature[14] = if (pitchFrames > 0) (pitchPeriodSum / pitchFrames).toFloat() * 2.5f else 0.3f
            rawFeature[15] = (totalRms / activeFrames).toFloat()

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
                "Could not verify your voice—please confirm manually."
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
                "Could not verify your voice—please confirm manually."
            )
            else -> VerificationOutcome(
                MatchStatus.DIFFERENT_SPEAKER,
                sim,
                "Could not verify your voice—please confirm manually. (Different speaker detected; sensitive actions paused)"
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
