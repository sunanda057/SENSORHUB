package com.sensorhub.app.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import androidx.core.content.ContextCompat

data class SensorMeta(
    val category: String,
    val description: String,
    val unit: String,
    val axes: List<String>,
)

data class HwSensor(
    val sensor: Sensor,
    val key: String,
    val title: String,
    val meta: SensorMeta,
) {
    val oneShot: Boolean get() = sensor.reportingMode == Sensor.REPORTING_MODE_ONE_SHOT
}

object SensorCatalog {
    private fun m(c: String, d: String, u: String, vararg a: String) = SensorMeta(c, d, u, a.toList())

    fun meta(type: Int): SensorMeta = when (type) {
        1 -> m("Accelerometer", "Measures acceleration (including gravity) along 3 axes. Used for tilt, shake and motion detection.", "m/s²", "X", "Y", "Z")
        2 -> m("Magnetic field", "Measures the surrounding magnetic field. Works as a compass.", "µT", "X", "Y", "Z")
        3 -> m("Orientation (deprecated)", "Device orientation as azimuth, pitch and roll angles.", "°", "Azimuth", "Pitch", "Roll")
        4 -> m("Gyroscope", "Measures rotation speed around 3 axes. Used for turning and spinning detection.", "rad/s", "X", "Y", "Z")
        5 -> m("Light", "Measures ambient light level. Used for automatic screen brightness.", "lx", "Illuminance")
        6 -> m("Barometer / pressure", "Measures atmospheric pressure. Used for altitude and weather estimation.", "hPa", "Pressure")
        7 -> m("Temperature (deprecated)", "Device temperature sensor.", "°C", "Temperature")
        8 -> m("Proximity", "Detects how close an object (e.g. your face during a call) is to the screen.", "cm", "Distance")
        9 -> m("Gravity", "Direction and strength of gravity, with motion removed.", "m/s²", "X", "Y", "Z")
        10 -> m("Linear acceleration", "Acceleration with gravity removed (pure movement).", "m/s²", "X", "Y", "Z")
        11 -> m("Rotation vector", "Device orientation in space, fused from accelerometer, gyroscope and magnetometer.", "", "X", "Y", "Z", "Cos", "Heading accuracy")
        12 -> m("Relative humidity", "Measures the humidity of the air.", "%", "Humidity")
        13 -> m("Ambient temperature", "Measures the air temperature around the device.", "°C", "Temperature")
        14 -> m("Magnetic field (uncalibrated)", "Raw magnetic field without hard-iron correction, plus the estimated bias.", "µT", "X", "Y", "Z", "Bias X", "Bias Y", "Bias Z")
        15 -> m("Game rotation vector", "Orientation from accelerometer and gyroscope only (no magnetic field). Used in games.", "", "X", "Y", "Z", "Cos")
        16 -> m("Gyroscope (uncalibrated)", "Raw rotation rate without drift compensation, plus the estimated drift.", "rad/s", "X", "Y", "Z", "Drift X", "Drift Y", "Drift Z")
        17 -> m("Significant motion", "Trigger sensor: fires once when significant movement (e.g. walking) starts.", "", "Triggered")
        18 -> m("Step detector", "Fires one event each time you take a step.", "", "Step")
        19 -> m("Step counter", "Total steps counted since the last reboot.", "steps", "Steps")
        20 -> m("Geomagnetic rotation vector", "Orientation from accelerometer and magnetometer only (low power).", "", "X", "Y", "Z", "Cos", "Heading accuracy")
        21 -> m("Heart rate", "Measures heart rate (needs a heart-rate sensor, mostly on wearables).", "bpm", "Heart rate")
        22 -> m("Tilt detector", "Detects when the device is tilted.", "", "Value")
        23 -> m("Wake gesture", "Detects a gesture that wakes the device.", "", "Value")
        24 -> m("Glance gesture", "Detects a glance gesture that briefly turns on the screen.", "", "Value")
        25 -> m("Pick-up gesture", "Detects when the device is picked up.", "", "Value")
        26 -> m("Wrist tilt gesture", "Detects a wrist tilt gesture.", "", "Value")
        27 -> m("Device orientation", "Reports screen orientation: 0 = portrait, 1 = landscape, 2 = reverse portrait, 3 = reverse landscape.", "", "Orientation")
        28 -> m("Pose 6DOF", "Full position and rotation of the device in 3D space.", "", "Value")
        29 -> m("Stationary detect", "Trigger sensor: fires when the device has been still for a while.", "", "Triggered")
        30 -> m("Motion detect", "Trigger sensor: fires when the device starts moving.", "", "Triggered")
        31 -> m("Heart beat", "Fires on each detected heart beat.", "", "Confidence")
        34 -> m("Low-latency off-body detect", "Detects whether a wearable is on or off the body (1 = on, 0 = off).", "", "Off-body state")
        35 -> m("Accelerometer (uncalibrated)", "Raw acceleration without bias compensation, plus the estimated bias.", "m/s²", "X", "Y", "Z", "Bias X", "Bias Y", "Bias Z")
        36 -> m("Hinge angle", "Angle of a foldable phone's hinge.", "°", "Angle")
        37 -> m("Head tracker", "Head orientation for spatial audio.", "", "Value")
        38 -> m("Accelerometer (limited axes)", "Acceleration on a limited set of axes (e.g. automotive).", "m/s²", "X", "Y", "Z", "Supported X", "Supported Y", "Supported Z")
        39 -> m("Gyroscope (limited axes)", "Rotation rate on a limited set of axes.", "rad/s", "X", "Y", "Z", "Supported X", "Supported Y", "Supported Z")
        42 -> m("Heading", "Compass heading in degrees relative to north.", "°", "Heading", "Accuracy")
        else -> m("Other sensor (type $type)", "Device-specific sensor. Raw values are shown exactly as reported by the hardware.", "")
    }

    /** Runtime permission needed before this sensor may deliver data (null = none needed). */
    fun permissionFor(type: Int): String? = when {
        (type == Sensor.TYPE_STEP_COUNTER || type == Sensor.TYPE_STEP_DETECTOR) && Build.VERSION.SDK_INT >= 29 ->
            Manifest.permission.ACTIVITY_RECOGNITION
        type == Sensor.TYPE_HEART_RATE || type == 31 -> Manifest.permission.BODY_SENSORS
        else -> null
    }

    fun has(ctx: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(ctx, permission) == PackageManager.PERMISSION_GRANTED
}

object SensorRegistry {
    private var cache: List<HwSensor>? = null

    fun load(ctx: Context): List<HwSensor> = cache ?: build(ctx).also { cache = it }

    private fun build(ctx: Context): List<HwSensor> {
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val seen = HashSet<String>()
        val out = ArrayList<HwSensor>()
        for (s in sm.getSensorList(Sensor.TYPE_ALL)) {
            if (s.type == 32 || s.type == 33) continue // dynamic-meta / additional-info are not real sensors
            val wake = s.isWakeUpSensor
            val key = "t${s.type}_" + s.name.replace(Regex("[^A-Za-z0-9]+"), "_").trim('_') + if (wake) "_wu" else ""
            if (!seen.add(key)) continue
            val title = s.name + if (wake) " (wake-up)" else ""
            out.add(HwSensor(s, key, title, SensorCatalog.meta(s.type)))
        }
        return out.sortedBy { it.sensor.type }
    }
}
