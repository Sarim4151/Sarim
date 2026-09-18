package com.hinnka.mycamera.processor

import android.os.Build

/** Local defaults for Photon's RAW imaging chain, shared by capture and reprocessing. */
object PhotonCoreImagingTuning {
    val fusion: PhotonFusionTuning =
        if (isCmfPhone1()) {
            PhotonFusionTuning.DEFAULT.copy(
                // Keep fine texture eligible for multiframe fusion on this sensor.
                mergeGradientThreshold = -1f,
                // The CMF RAW noise model is already measured; don't artificially
                // inflate it before Sabre decides how much detail to retain.
                noiseCorrelationScale = 0.92f,
            )
        } else {
            PhotonFusionTuning.DEFAULT
        }

    val denoise: PhotonDenoiseTuning =
        if (isCmfPhone1()) {
            PhotonDenoiseTuning.DEFAULT.copy(
                // Slightly relax luma denoising while keeping coarse-scale cleanup intact.
                lumaStrengthScale = PhotonPyramidScales(
                    level1 = 0.96f,
                    level2 = 0.96f,
                    level3 = 0.98f,
                    level4 = 1f,
                    level5 = 1f,
                ),
                // Restore a little more of the original high-frequency signal after denoise.
                detailReconstructionScale = PhotonPyramidScales(
                    level1 = 1.10f,
                    level2 = 1.08f,
                    level3 = 1.04f,
                    level4 = 1f,
                    level5 = 1f,
                ),
                // Keep rejection conservative; don't trade texture for noise suppression.
                outlierRejectionScale = PhotonPyramidScales(
                    level1 = 0.98f,
                    level2 = 0.98f,
                    level3 = 0.99f,
                    level4 = 1f,
                    level5 = 1f,
                ),
                // Chroma remains at the Photon default to avoid coloured noise.
                chromaStrengthScale = PhotonPyramidScales.IDENTITY,
            )
        } else {
            PhotonDenoiseTuning.DEFAULT
        }
    /**
     * Local SNR/contrast curves for the original MGC final sharpening kernel.
     *
     * CMF Phone 1 gets a restrained detail lift only in the first two native
     * frequency groups. Band 2 remains unchanged to avoid amplifying coarse
     * texture/halos. All other devices keep the Photon default unchanged.
     */
    val sharpen: PhotonSharpenTuning =
        if (isCmfPhone1()) {
            PhotonSharpenTuning.DEFAULT.copy(
                amount = PhotonSharpenBands(
                    band0 = 1.08f,
                    band1 = 1.05f,
                    band2 = 1f,
                ),
            )
        } else {
            PhotonSharpenTuning.DEFAULT
        }
    private fun isCmfPhone1(): Boolean =
        Build.MANUFACTURER.equals("Nothing", ignoreCase = true) &&
            Build.MODEL.equals("A015", ignoreCase = true)

    /** Dehaze + DHA baked into HDRNet's ProfileGainTableMap output. */
    val dehaze: PhotonDehazeTuning = PhotonDehazeTuning.DEFAULT
}

/** Controls frame admission and propagated noise behavior in multi-frame fusion. */
data class PhotonFusionTuning(
    /**
     * AGC lib_sabre_denoise_control_key: -1 disables weak-texture broadening of the
     * Sabre fusion kernel. Null restores the original SNR-adaptive threshold.
     */
    val mergeGradientThreshold: Float? = -1f,
    /** Normalized green signal used only when reference-frame measurement is unavailable. */
    val missingReferenceSignal: Float = DEFAULT_MISSING_REFERENCE_SIGNAL,
    /**
     * Scales Sabre's 128-bin spectrum before noise-pyramid propagation. The
     * separable 2D noise energy scales quadratically; zero means zero modeled
     * noise energy. Spatial preserves its measured spectrum without this override.
     */
    val noiseCorrelationScale: Float = 1f,
) {
    fun normalized(): PhotonFusionTuning = copy(
        mergeGradientThreshold = mergeGradientThreshold
            ?.takeIf(Float::isFinite)
            ?.coerceIn(-32f, 32f),
        missingReferenceSignal = missingReferenceSignal
            .takeIf(Float::isFinite)
            ?.coerceIn(0f, 1f)
            ?: DEFAULT_MISSING_REFERENCE_SIGNAL,
        noiseCorrelationScale = noiseCorrelationScale
            .takeIf(Float::isFinite)
            ?.coerceIn(0f, 8f)
            ?: 1f,
    )

    companion object {
        const val DEFAULT_MISSING_REFERENCE_SIGNAL = 0.18f
        val DEFAULT = PhotonFusionTuning()
    }
}

/** Fixed five-level pyramid multiplier with structural equality. */
data class PhotonPyramidScales(
    val level1: Float = 1f,
    val level2: Float = 1f,
    val level3: Float = 1f,
    val level4: Float = 1f,
    val level5: Float = 1f,
) {
    fun toFloatArray(): FloatArray = floatArrayOf(level1, level2, level3, level4, level5)

    fun normalized(): PhotonPyramidScales = map { value ->
        value.takeIf(Float::isFinite)?.coerceIn(0f, MAX_SCALE) ?: 1f
    }

    private fun map(transform: (Float) -> Float): PhotonPyramidScales = PhotonPyramidScales(
        transform(level1),
        transform(level2),
        transform(level3),
        transform(level4),
        transform(level5),
    )

    companion object {
        private const val MAX_SCALE = 16f
        val IDENTITY = PhotonPyramidScales()

        fun uniform(value: Float): PhotonPyramidScales = PhotonPyramidScales(
            level1 = value,
            level2 = value,
            level3 = value,
            level4 = value,
            level5 = value,
        ).normalized()
    }
}

/** Direct controls for the full-resolution luma/chroma denoise stage. */
data class PhotonDenoiseTuning(
    val lumaStrengthScale: PhotonPyramidScales = PhotonPyramidScales.IDENTITY,
    /** Multiplies the luma revert-factor field: higher values restore the source more strongly. */
    val detailReconstructionScale: PhotonPyramidScales = PhotonPyramidScales.IDENTITY,
    val outlierRejectionScale: PhotonPyramidScales = PhotonPyramidScales.IDENTITY,
    val chromaStrengthScale: PhotonPyramidScales = PhotonPyramidScales.IDENTITY,
    val frequencyResponse: PhotonDenoiseFrequencyResponse = PhotonDenoiseFrequencyResponse.DEFAULT,
    /** Optional absolute Sabre luma strength nodes at SNR 5/20/40, before interpolation. */
    val sabreLumaNodes: PhotonSabreLumaTuningNodes = PhotonSabreLumaTuningNodes.DEFAULT,
    /** Independent absolute protobuf fields, also overridden before SNR interpolation. */
    val sabreLumaRevertNodes: PhotonSabreLumaTuningNodes = PhotonSabreLumaTuningNodes.DEFAULT,
    val sabreLumaOutlierNodes: PhotonSabreLumaTuningNodes = PhotonSabreLumaTuningNodes.DEFAULT,
    /** Initial value of a default 128-bin spectrum; never a multiplier for measured spectra. */
    val noiseSpectrumSeed: Float = DEFAULT_SPECTRUM_SEED,
) {
    fun normalized(): PhotonDenoiseTuning = copy(
        lumaStrengthScale = lumaStrengthScale.normalized(),
        detailReconstructionScale = detailReconstructionScale.normalized(),
        outlierRejectionScale = outlierRejectionScale.normalized(),
        chromaStrengthScale = chromaStrengthScale.normalized(),
        frequencyResponse = frequencyResponse.normalized(),
        sabreLumaNodes = sabreLumaNodes.normalized(),
        sabreLumaRevertNodes = sabreLumaRevertNodes.normalized(allowNegative = true),
        sabreLumaOutlierNodes = sabreLumaOutlierNodes.normalized(allowNegative = true),
        noiseSpectrumSeed = noiseSpectrumSeed.takeIf(Float::isFinite)
            ?.coerceIn(0f, 8f) ?: DEFAULT_SPECTRUM_SEED,
    )

    companion object {
        const val DEFAULT_SPECTRUM_SEED = 1f
        val DEFAULT = PhotonDenoiseTuning()
    }
}

/** Constants in the denoise pyramid response `(responseOffset-cos²)+(cos+cosineOffset)²`. */
data class PhotonDenoiseFrequencyResponse(
    val responseOffset: Float = DEFAULT_RESPONSE_OFFSET,
    val cosineOffset: Float = -1f,
) {
    fun normalized(): PhotonDenoiseFrequencyResponse = copy(
        responseOffset = responseOffset.takeIf(Float::isFinite)?.coerceIn(-32f, 32f)
            ?: DEFAULT_RESPONSE_OFFSET,
        cosineOffset = cosineOffset.takeIf(Float::isFinite)?.coerceIn(-32f, 32f) ?: -1f,
    )

    companion object {
        const val DEFAULT_RESPONSE_OFFSET = 1f
        val DEFAULT = PhotonDenoiseFrequencyResponse()
    }
}

/** Absolute field overrides. Null preserves the asset; signed revert/outlier values are literal. */
data class PhotonPyramidOverrides(
    val level1: Float? = null,
    val level2: Float? = null,
    val level3: Float? = null,
    val level4: Float? = null,
    val level5: Float? = null,
) {
    fun toList(): List<Float?> = listOf(level1, level2, level3, level4, level5)

    fun normalized(allowNegative: Boolean = false): PhotonPyramidOverrides {
        val values = toList().map { value ->
            value?.takeIf(Float::isFinite)?.coerceIn(if (allowNegative) -32f else 0f, 32f)
        }
        return PhotonPyramidOverrides(values[0], values[1], values[2], values[3], values[4])
    }
}

/** SNR nodes for one independent luma protobuf field (strength, revert, or outlier). */
data class PhotonSabreLumaTuningNodes(
    val snr5: PhotonPyramidOverrides = PhotonPyramidOverrides(),
    val snr20: PhotonPyramidOverrides = PhotonPyramidOverrides(),
    val snr40: PhotonPyramidOverrides = PhotonPyramidOverrides(),
) {
    fun normalized(allowNegative: Boolean = false): PhotonSabreLumaTuningNodes = copy(
        snr5 = snr5.normalized(allowNegative),
        snr20 = snr20.normalized(allowNegative),
        snr40 = snr40.normalized(allowNegative),
    )

    fun valuesForSnr(snr: Float): List<Float?>? = when (snr) {
        SNR_5 -> snr5.toList()
        SNR_20 -> snr20.toList()
        SNR_40 -> snr40.toList()
        else -> null
    }

    companion object {
        const val SNR_5 = 5f
        const val SNR_20 = 20f
        const val SNR_40 = 40f
        val DEFAULT = PhotonSabreLumaTuningNodes()
    }
}

/**
 * Controls for Dehaze + DHA applied to HDRNet output and baked into its PGTM.
 *
 * [strength] scales the two estimated atmospheric haze points before curve construction.
 * [dynamicHighlightStrength] controls how much of the histogram-derived highlight scale is used;
 * zero preserves the original white scale and one applies the complete dynamic adjustment.
 */
data class PhotonDehazeTuning(
    val enabled: Boolean = true,
    val strength: Float = 1f,
    val dynamicHighlightStrength: Float = 1f,
) {
    fun normalized(): PhotonDehazeTuning = copy(
        strength = strength
            .takeIf(Float::isFinite)
            ?.coerceIn(0f, 4f)
            ?: 1f,
        dynamicHighlightStrength = dynamicHighlightStrength
            .takeIf(Float::isFinite)
            ?.coerceIn(0f, 1f)
            ?: 1f,
    )

    val isActive: Boolean
        get() = normalized().let {
            it.enabled && (it.strength > 0f || it.dynamicHighlightStrength > 0f)
        }

    companion object {
        val DEFAULT = PhotonDehazeTuning()
        val DISABLED = PhotonDehazeTuning(enabled = false)
    }
}
