package com.hinnka.mycamera.raw

import android.os.Build

/**
 * Camera-specific calibration recovered from native CMF Phone 1 (A015) DNG metadata.
 *
 * These are static DNG calibration tags, not a creative look:
 * - ColorMatrix1/2: XYZ -> reference camera RGB
 * - ForwardMatrix1/2: white-balanced reference camera RGB -> XYZ D50
 * - Illuminants: D65 (21) and Standard Light A (17)
 *
 * The matrices are intentionally kept here instead of replacing the generic Camera2
 * calibration globally. The profile is selected only on the CMF Phone 1.
 */
internal object CmfPhone1Calibration {
    private const val MANUFACTURER = "Nothing"
    private const val MODEL = "A015"

    const val WHITE_LEVEL = 1023f

    // DNG BlackLevel: 6395/100, 6395/100, 6403/100, 6403/100.
    val BLACK_LEVEL = floatArrayOf(
        63.95f, 63.95f, 64.03f, 64.03f
    )

    val profile: DcpProfile = DcpProfile(
        profileName = "CMF Phone 1 A015 DNG Calibration",
        calibrationIlluminant1 = 21,
        calibrationIlluminant2 = 17,
        baselineExposureOffset = 0f,
        defaultBlackRender = DcpDefaultBlackRender.Auto,
        supportsOverrange = false,
        colorMatrix1 = floatArrayOf(
            0.666793823f, -0.158889771f, -0.085739136f,
            -0.573944092f, 1.389785767f, 0.143020630f,
            -0.137878418f, 0.265151978f, 0.603622437f,
        ),
        colorMatrix2 = floatArrayOf(
            1.531463623f, -0.469604492f, -0.215057373f,
            -0.476226807f, 1.445327759f, 0.006698608f,
            -0.071746826f, 0.238723755f, 0.232955933f,
        ),
        forwardMatrix1 = floatArrayOf(
            0.673141479f, 0.195037842f, 0.096023560f,
            0.276184082f, 0.818206787f, -0.094406128f,
            0.021652222f, -0.232452393f, 1.036010742f,
        ),
        forwardMatrix2 = floatArrayOf(
            0.574493408f, 0.184005737f, 0.205703735f,
            0.193817139f, 0.745376587f, 0.060791016f,
            -0.014495850f, -0.528686523f, 1.368408203f,
        ),
        hueSatDeltas1 = null,
        hueSatDeltas2 = null,
        lookTable = null,
        toneCurve = null,
    )

    fun isSupported(): Boolean =
        Build.MANUFACTURER.equals(MANUFACTURER, ignoreCase = true) &&
            Build.MODEL.equals(MODEL, ignoreCase = true)

    fun cameraCalibration(): RawCameraCalibration =
        requireNotNull(RawCameraCalibration.fromProfile(profile))

    fun colorMatrixFor(
        whiteBalanceGains: FloatArray,
        workingColorSpace: ColorSpace,
    ): FloatArray? = DngSdkColorSpec.computeCameraToWorkingMatrix(
        colorMatrix1 = profile.colorMatrix1,
        colorMatrix2 = profile.colorMatrix2,
        forwardMatrix1 = profile.forwardMatrix1,
        forwardMatrix2 = profile.forwardMatrix2,
        calibrationIlluminant1 = profile.calibrationIlluminant1,
        calibrationIlluminant2 = profile.calibrationIlluminant2,
        whiteBalanceGains = whiteBalanceGains,
        workingColorSpace = workingColorSpace,
        analogBalance = profile.analogBalance,
        cameraCalibration1 = profile.cameraCalibration1,
        cameraCalibration2 = profile.cameraCalibration2,
    )

    fun cameraWhiteFor(whiteBalanceGains: FloatArray): FloatArray? =
        DngSdkColorSpec.computeCameraWhite(
            colorMatrix1 = profile.colorMatrix1,
            colorMatrix2 = profile.colorMatrix2,
            forwardMatrix1 = profile.forwardMatrix1,
            forwardMatrix2 = profile.forwardMatrix2,
            calibrationIlluminant1 = profile.calibrationIlluminant1,
            calibrationIlluminant2 = profile.calibrationIlluminant2,
            whiteBalanceGains = whiteBalanceGains,
            analogBalance = profile.analogBalance,
            cameraCalibration1 = profile.cameraCalibration1,
            cameraCalibration2 = profile.cameraCalibration2,
        )

    fun whitePointFor(whiteBalanceGains: FloatArray): FloatArray? =
        DngSdkColorSpec.computeWhiteXy(
            colorMatrix1 = profile.colorMatrix1,
            colorMatrix2 = profile.colorMatrix2,
            forwardMatrix1 = profile.forwardMatrix1,
            forwardMatrix2 = profile.forwardMatrix2,
            calibrationIlluminant1 = profile.calibrationIlluminant1,
            calibrationIlluminant2 = profile.calibrationIlluminant2,
            whiteBalanceGains = whiteBalanceGains,
            analogBalance = profile.analogBalance,
            cameraCalibration1 = profile.cameraCalibration1,
            cameraCalibration2 = profile.cameraCalibration2,
        )
}
