package com.hinnka.mycamera.raw

import android.opengl.GLES30
import android.opengl.GLES31
import com.hinnka.mycamera.processor.GlesGpuCompletion
import com.hinnka.mycamera.processor.PhotonCoreImagingTuning
import com.hinnka.mycamera.processor.PhotonSharpenTuning
import com.hinnka.mycamera.utils.LargeDirectBuffer
import com.hinnka.mycamera.utils.PLog
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Original MGC AOT kernel at Photon's encoded RGBA8 boundary, with reusable native storage. */
internal class MgcSharpen {
    private var transferBuffer = 0
    private var transferSize = 0
    private var scratch: ByteBuffer? = null
    private var packProgram = 0
    private var packAttempted = false
    private var maxSsboBytes = 0L

    fun render(
        sourceTexture: Int,
        sourceFramebuffer: Int,
        targetTexture: Int,
        width: Int,
        height: Int,
        snr: Float,
        attenuation: Float,
        tuning: PhotonSharpenTuning = PhotonCoreImagingTuning.sharpen,
    ) {
        require(width > 0 && height > 0 && snr.isFinite() && snr > 0f)
        require(attenuation.isFinite() && attenuation >= 0f)
        val curves = MgcSharpenCurveBuilder.build(snr, tuning)
        val pixels = width.toLong() * height
        val outputPixels = width.toLong() * ((height.toLong() + 1) and -2L)
        val scratchBytes = (pixels + outputPixels) * 6
        require(scratchBytes <= Int.MAX_VALUE)
        val bytes = (pixels * 4).toInt()
        val start = System.nanoTime()
        ensureStorage(bytes, scratchBytes)
        val allocationMs = elapsedMs(start)
        val useCompute = bytes <= maxSsboBytes && packProgram != 0
        val upstreamWaitMs = GlesGpuCompletion.awaitSubmittedWork(
            label = "MGC sharpen input", checkGlError = ::checkGlError,
        )
        val transferStart = System.nanoTime()
        try {
            if (useCompute) {
                GLES31.glUseProgram(packProgram)
                GLES31.glActiveTexture(GLES31.GL_TEXTURE0)
                GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, sourceTexture)
                GLES31.glUniform1i(GLES31.glGetUniformLocation(packProgram, "uInput"), 0)
                GLES31.glUniform2i(GLES31.glGetUniformLocation(packProgram, "uSize"), width, height)
                GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 0, transferBuffer)
                GLES31.glDispatchCompute((width + 7) / 8, (height + 7) / 8, 1)
                GLES31.glMemoryBarrier(GLES31.GL_SHADER_STORAGE_BARRIER_BIT or GLES31.GL_BUFFER_UPDATE_BARRIER_BIT)
                GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 0, 0)
            } else {
                GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, sourceFramebuffer)
                GLES30.glReadBuffer(GLES30.GL_COLOR_ATTACHMENT0)
                GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, transferBuffer)
                GLES30.glPixelStorei(GLES30.GL_PACK_ALIGNMENT, 1)
                GLES30.glPixelStorei(GLES30.GL_PACK_ROW_LENGTH, 0)
                GLES30.glReadPixels(0, 0, width, height, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, 0)
            }
            checkGlError("MGC sharpen transfer submission")
            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, transferBuffer)
            val mapped = checkNotNull(GLES30.glMapBufferRange(
                GLES30.GL_PIXEL_PACK_BUFFER, 0, bytes,
                GLES30.GL_MAP_READ_BIT or GLES30.GL_MAP_WRITE_BIT,
            ) as? ByteBuffer) { "MGC sharpen transfer mapping failed" }
            val transferMs = elapsedMs(transferStart)
            val nativeStart = System.nanoTime()
            try {
                val result = nativeSharpenRgba8(mapped.order(ByteOrder.nativeOrder()),
                    checkNotNull(scratch), width, height, snr, attenuation, curves.points)
                check(result == 0) { "MGC original sharpen failed: $result" }
            } finally {
                check(GLES30.glUnmapBuffer(GLES30.GL_PIXEL_PACK_BUFFER)) {
                    "MGC sharpen mapped buffer became invalid"
                }
            }
            val nativeMs = elapsedMs(nativeStart)
            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
            val uploadStart = System.nanoTime()
            check(nativeUploadRgba8(transferBuffer, targetTexture, width, height)) {
                "MGC sharpen PBO upload failed"
            }
            PLog.i(TAG, "originalMgc=true size=${width}x$height referenceSnr=$snr " +
                "attenuation=$attenuation transfer=${if (useCompute) "SSBO" else "PBO"} " +
                "curveSnr=${curves.lowerSnr}:${curves.upperSnr} curveMix=${curves.interpolation} " +
                "bandAmount=${tuning.amount} mainGains=${curves.mainGains} " +
                "allocationMs=$allocationMs upstreamGpuWaitMs=$upstreamWaitMs " +
                "transferAndMapMs=$transferMs nativeAndUnmapMs=$nativeMs " +
                "uploadSubmitMs=${elapsedMs(uploadStart)} totalCpuMs=${elapsedMs(start)}")
        } finally {
            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
            GLES30.glBindBuffer(GLES30.GL_PIXEL_UNPACK_BUFFER, 0)
            GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, 0)
        }
    }

    private fun ensureStorage(bytes: Int, scratchBytes: Long) {
        if (!packAttempted) {
            packAttempted = true
            val major = IntArray(1)
            val minor = IntArray(1)
            GLES30.glGetIntegerv(GLES30.GL_MAJOR_VERSION, major, 0)
            GLES30.glGetIntegerv(GLES30.GL_MINOR_VERSION, minor, 0)
            if (major[0] > 3 || major[0] == 3 && minor[0] >= 1) {
                val limit = LongArray(1)
                GLES31.glGetInteger64v(GLES31.GL_MAX_SHADER_STORAGE_BLOCK_SIZE, limit, 0)
                maxSsboBytes = limit[0]
                packProgram = RawGlesProgram.compileCompute(PACK_RGBA8, "MgcSharpenPackRgba8")
            }
        }
        if (transferBuffer == 0) {
            val names = IntArray(1)
            GLES30.glGenBuffers(1, names, 0)
            transferBuffer = names[0]
        }
        if (transferSize < bytes) {
            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, transferBuffer)
            GLES30.glBufferData(GLES30.GL_PIXEL_PACK_BUFFER, bytes, null, GLES30.GL_STREAM_COPY)
            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
            checkGlError("MGC sharpen transfer allocation")
            transferSize = bytes
        }
        if ((scratch?.capacity()?.toLong() ?: 0) < scratchBytes) {
            LargeDirectBuffer.free(scratch)
            scratch = null
            scratch = checkNotNull(LargeDirectBuffer.allocate(scratchBytes, "MGC sharpen YUV/RGB"))
        }
    }

    /** Release frame storage after all tiles; programs live with the GL context. */
    fun releaseBuffers() {
        LargeDirectBuffer.free(scratch)
        scratch = null
        if (transferBuffer != 0) GLES30.glDeleteBuffers(1, intArrayOf(transferBuffer), 0)
        transferBuffer = 0
        transferSize = 0
    }

    fun release() {
        releaseBuffers()
        if (packProgram != 0) GLES30.glDeleteProgram(packProgram)
        packProgram = 0
        packAttempted = false
        maxSsboBytes = 0
    }

    private external fun nativeSharpenRgba8(
        rgba: ByteBuffer, scratch: ByteBuffer, width: Int, height: Int, snr: Float, attenuation: Float,
        curves: FloatArray,
    ): Int
    private external fun nativeUploadRgba8(pbo: Int, texture: Int, width: Int, height: Int): Boolean

    companion object {
        private const val TAG = "MgcSharpen"
        init { System.loadLibrary("my-native-lib") }
        private fun elapsedMs(start: Long) = (System.nanoTime() - start) / 1_000_000.0
        private fun checkGlError(label: String) {
            val error = GLES30.glGetError()
            check(error == GLES30.GL_NO_ERROR) { "$label: GL error 0x${error.toString(16)}" }
        }
        internal val PACK_RGBA8 = """
            #version 310 es
            precision highp float;
            precision highp int;
            layout(local_size_x = 8, local_size_y = 8) in;
            uniform highp sampler2D uInput;
            uniform ivec2 uSize;
            layout(std430, binding = 0) writeonly buffer Pixels { uint pixels[]; };
            void main() {
                ivec2 p = ivec2(gl_GlobalInvocationID.xy);
                if (any(greaterThanEqual(p, uSize))) return;
                uvec4 v = uvec4(round(texelFetch(uInput, p, 0) * 255.0));
                pixels[p.y * uSize.x + p.x] = v.r | (v.g << 8u) | (v.b << 16u) | (v.a << 24u);
            }
        """.trimIndent()
    }
}
