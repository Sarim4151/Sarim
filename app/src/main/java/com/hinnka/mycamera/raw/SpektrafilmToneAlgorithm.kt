package com.hinnka.mycamera.raw

import android.opengl.GLES30
import com.hinnka.mycamera.utils.PLog
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object SpektrafilmToneShader {
    val SPECTRAL_FILM_COMBINED_UNIFORMS = """
        uniform highp sampler3D uSpectralFilmTexture;
        uniform highp sampler3D uSpectralPrintTexture;
        uniform mat3 uOutputTransform;
        uniform int uSpectralFilmSize;
        uniform int uSpectralPrintSize;
        uniform float uSpectralInputScale;
        uniform float uSpectralPrintInputScale;
        uniform vec3 uSpectralNegativeDensityGains;
    """.trimIndent()
    val SPECTRAL_FILM_COMBINED_FUNCTIONS = """
        vec3 linearToProPhoto(vec3 color) {
            vec3 clamped = max(color, vec3(0.0));
            vec3 isHigh = step(vec3(0.001953125), clamped);
            vec3 lowPart = 16.0 * clamped;
            vec3 highPart = pow(clamped, vec3(1.0 / 1.8));
            return mix(lowPart, highPart, isHigh);
        }

        vec3 proPhotoToLinear(vec3 color) {
            vec3 clamped = clamp(color, 0.0, 1.0);
            vec3 isHigh = step(vec3(0.03125), clamped);
            vec3 lowPart = clamped / 16.0;
            vec3 highPart = pow(clamped, vec3(1.8));
            return mix(lowPart, highPart, isHigh);
        }

        vec3 spectralLutCoordinate(vec3 value, int size) {
            // Model samples span lattice indices 0..N-1; GL samples texel centers.
            float lutSize = float(size);
            return (clamp(value, 0.0, 1.0) * (lutSize - 1.0) + 0.5) / lutSize;
        }

        vec3 applySpectralFilm(vec3 color) {
            if (uSpectralFilmSize <= 1) {
                return color;
            }
            vec3 encodedColor = linearToProPhoto(color / uSpectralInputScale);
            vec3 filmResult = texture(
                uSpectralFilmTexture,
                spectralLutCoordinate(encodedColor, uSpectralFilmSize)
            ).rgb;
            if (uSpectralPrintSize > 1) {
                // The film LUT contains normalized CMY densities. Apply the user's
                // negative-density gain before the independent print/scanner LUT.
                // Its lattice spans 0..1.5 to cover every supported density gain.
                vec3 printInput = filmResult * uSpectralNegativeDensityGains / uSpectralPrintInputScale;
                filmResult = texture(
                    uSpectralPrintTexture,
                    spectralLutCoordinate(printInput, uSpectralPrintSize)
                ).rgb;
            }
            // Positive film LUTs already include scanning; both paths end in
            // encoded ProPhoto RGB and share the same linear output contract.
            return proPhotoToLinear(filmResult);
        }

        vec3 applyEngineTone(vec3 color) {
            return uOutputTransform * applySpectralFilm(color);
        }
    """.trimIndent()

    val DEFINITION = RawEngineToneShaderDefinition(
        engineUniforms = SPECTRAL_FILM_COMBINED_UNIFORMS,
        engineFunctions = SPECTRAL_FILM_COMBINED_FUNCTIONS,
        includeAdobeProfilePipeline = false,
    )
}

internal class SpektrafilmToneAlgorithm(quad: RawFullscreenQuad) :
    RawRenderingEngineToneAlgorithm(quad, SpektrafilmToneShader.DEFINITION) {
    // Shared tone resources occupy 0 (input), 7 (gain table), 2 (HDR coordinate),
    // and 4 (HDR base curve). Keep both 3D samplers explicit on every draw.
    private val filmTexture = LutTexture(6, "film")
    private val printTexture = LutTexture(5, "print")

    override fun bindEngineResources(program: Int, input: RawEngineTonePass.Input) {
        super.bindEngineResources(program, input)
        val lut = input.spectralFilmLut
        filmTexture.bind(program, "uSpectralFilmTexture", lut?.filmTable)
        printTexture.bind(program, "uSpectralPrintTexture", lut?.printTable)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uSpectralFilmSize"), lut?.size ?: 1)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uSpectralPrintSize"), lut?.printTable?.size ?: 1)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "uSpectralInputScale"), lut?.inputScale ?: 1f)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "uSpectralPrintInputScale"), lut?.printInputScale ?: 1f)
        val gains = lut?.negativeDensityGains
        GLES30.glUniform3f(
            GLES30.glGetUniformLocation(program, "uSpectralNegativeDensityGains"),
            gains?.get(0) ?: 1f, gains?.get(1) ?: 1f, gains?.get(2) ?: 1f,
        )
        RawGlesProgram.logErrors("SpektrafilmToneAlgorithm.bindEngineResources")
    }

    override fun releaseEngineResources() {
        filmTexture.release()
        printTexture.release()
    }

    private class LutTexture(private val unit: Int, private val stage: String) {
        private var textureId = 0
        private var textureKey: String? = null

        fun bind(program: Int, uniform: String, table: SpectralLutTable?) {
            GLES30.glUniform1i(GLES30.glGetUniformLocation(program, uniform), unit)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
            val key = table?.sourceKey ?: FALLBACK_KEY
            if (textureId == 0 || textureKey != key) upload(table, key)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, textureId)
        }

        fun release() {
            if (textureId != 0) {
                GLES30.glDeleteTextures(1, intArrayOf(textureId), 0)
                textureId = 0
            }
            textureKey = null
        }

        private fun upload(table: SpectralLutTable?, key: String) {
            val size = table?.size ?: 1
            val limit = IntArray(1)
            GLES30.glGetIntegerv(GLES30.GL_MAX_3D_TEXTURE_SIZE, limit, 0)
            require(size <= limit[0]) { "Spektrafilm $stage LUT size $size exceeds GL_MAX_3D_TEXTURE_SIZE=${limit[0]}" }
            val values = table?.values ?: floatArrayOf(0f, 0f, 0f, 1f)
            require(values.size == size * size * size * 4) { "Invalid Spektrafilm $stage lattice size" }
            if (textureId == 0) {
                textureId = IntArray(1).also { GLES30.glGenTextures(1, it, 0) }[0]
                check(textureId != 0) { "Unable to allocate Spektrafilm $stage texture" }
            }
            textureKey = null
            // Only allocate/upload when the source asset changes, never for density
            // adjustments or subsequent frames. Null stages bind a valid 1³ texture.
            val buffer = ByteBuffer.allocateDirect(values.size * Float.SIZE_BYTES)
                .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                    put(values)
                    position(0)
                }
            GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, textureId)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_R, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glBindBuffer(GLES30.GL_PIXEL_UNPACK_BUFFER, 0)
            GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 4)
            GLES30.glPixelStorei(GLES30.GL_UNPACK_ROW_LENGTH, 0)
            GLES30.glPixelStorei(GLES30.GL_UNPACK_IMAGE_HEIGHT, 0)
            GLES30.glPixelStorei(GLES30.GL_UNPACK_SKIP_PIXELS, 0)
            GLES30.glPixelStorei(GLES30.GL_UNPACK_SKIP_ROWS, 0)
            GLES30.glPixelStorei(GLES30.GL_UNPACK_SKIP_IMAGES, 0)
            GLES30.glTexImage3D(
                GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGBA16F, size, size, size, 0,
                GLES30.GL_RGBA, GLES30.GL_FLOAT, buffer,
            )
            val error = GLES30.glGetError()
            check(error == GLES30.GL_NO_ERROR) {
                "Spektrafilm $stage LUT upload failed: GL error=0x${error.toString(16)}, size=$size, format=RGBA16F, unit=$unit"
            }
            textureKey = key
            PLog.d(TAG, "Uploaded Spektrafilm $stage LUT: size=$size, format=RGBA16F, " +
                "sampling=texel-center/highp, pipeline=film-density-print, " +
                "linearPipeline=highp-sampler2D, unit=$unit, source=$key")
        }
    }

    private companion object {
        const val TAG = "SpektrafilmToneAlgorithm"
        const val FALLBACK_KEY = "fallback"
    }
}
