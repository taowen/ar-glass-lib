package com.taowen.arglass.driver.rayneo

import android.content.Context
import android.content.SharedPreferences
import com.taowen.arglass.driver.rayneo.airfamily.RayneoMagneticCalibration

internal object RayneoMagneticCalibrationStore {
    private var preferences: SharedPreferences? = null

    @Synchronized
    fun initialize(context: Context) {
        if (preferences == null) {
            preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        }
    }

    @Synchronized
    fun load(deviceKey: String): RayneoMagneticCalibration? {
        val encoded = preferences?.getString(deviceKey, null) ?: return null
        val values = encoded.split(',').mapNotNull(String::toFloatOrNull)
        if (values.size != ENCODED_FLOATS || values.any { !it.isFinite() }) return null
        return RayneoMagneticCalibration(
            bias = values.subList(0, 3).toFloatArray(),
            correctionMatrix = values.subList(3, 12).toFloatArray(),
        )
    }

    @Synchronized
    fun save(deviceKey: String, calibration: RayneoMagneticCalibration) {
        val encoded = (calibration.bias.asList() + calibration.correctionMatrix.asList()).joinToString(",")
        preferences?.edit()?.putString(deviceKey, encoded)?.apply()
    }

    @Synchronized
    fun clear(deviceKey: String) {
        preferences?.edit()?.remove(deviceKey)?.apply()
    }

    private const val PREFERENCES = "rayneo_magnetic_calibration_v1"
    private const val ENCODED_FLOATS = 12
}
