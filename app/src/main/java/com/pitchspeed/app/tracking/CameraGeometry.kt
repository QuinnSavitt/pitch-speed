package com.pitchspeed.app.tracking

import android.hardware.camera2.CameraCharacteristics
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraInfo
import kotlin.math.atan
import kotlin.math.max

/**
 * Reads the physical sensor width and focal length off the Camera2 characteristics behind a
 * CameraX [CameraInfo] to compute the camera's horizontal field of view. That angle is what lets
 * us turn a ball's pixel position into a real-world angle, and — combined with the user-entered
 * distance to the throwing line — into a real-world position. Falls back to a sane phone-camera
 * default (68 degrees) if the device doesn't expose the characteristics.
 */
@androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
fun horizontalFovRadians(cameraInfo: CameraInfo): Double {
    return try {
        val characteristics = Camera2CameraInfo.from(cameraInfo)
        val sensorSize = characteristics.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val focalLengths = characteristics.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
        val focalLength = focalLengths?.firstOrNull()
        if (sensorSize != null && focalLength != null && focalLength > 0f) {
            val width = max(sensorSize.width, sensorSize.height) // physical long edge
            2.0 * atan((width / (2.0 * focalLength)))
        } else {
            Math.toRadians(68.0)
        }
    } catch (e: Exception) {
        Math.toRadians(68.0)
    }
}
