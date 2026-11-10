package com.sensorhub.app.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.sensorhub.app.data.Cloud
import com.sensorhub.app.data.CloudReading
import com.sensorhub.app.data.SensorCatalog
import com.sensorhub.app.data.SensorRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

private const val MAX_HISTORY = 100

/* ------------------------------------------------------------------ */
/*  Generic hardware sensor                                            */
/* ------------------------------------------------------------------ */

@Composable
fun HwSensorScreen(index: Int, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val hw = remember { SensorRegistry.load(ctx).getOrNull(index) }
    if (hw == null) {
        AppScaffold("Sensor", onBack) { Text("Sensor not found.") }
        return
    }
    val sensor = hw.sensor
    val permission = remember { SensorCatalog.permissionFor(sensor.type) }
    var granted by remember { mutableStateOf(permission == null || SensorCatalog.has(ctx, permission)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted && permission != null) launcher.launch(permission) }

    var values by remember { mutableStateOf(listOf<Float>()) }
    var accuracy by remember { mutableIntStateOf(-1) }
    var lastTs by remember { mutableLongStateOf(0L) }
    var samples by remember { mutableIntStateOf(0) }
    var uploaded by remember { mutableIntStateOf(0) }
    var syncOn by remember { mutableStateOf(true) }
    val syncRef by rememberUpdatedState(syncOn)
    var history by remember { mutableStateOf(listOf<List<Float>>()) }
    var cloud by remember { mutableStateOf(listOf<CloudReading>()) }

    // Live sensor stream - starts only after permission is granted.
    DisposableEffect(granted) {
        if (!granted) return@DisposableEffect onDispose { }
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        var lastWrite = 0L

        fun record(v: FloatArray, acc: Int) {
            val now = System.currentTimeMillis()
            val list = v.toList()
            values = list
            accuracy = acc
            lastTs = now
            samples += 1
            history = (history + listOf(list)).takeLast(MAX_HISTORY)
            if (syncRef && (hw.oneShot || now - lastWrite >= 1000)) {
                lastWrite = now
                Cloud.writeReading(hw.key, sensor.name, sensor.type, list, acc, now)
                uploaded += 1
            }
        }

        if (hw.oneShot) {
            val trigger = object : TriggerEventListener() {
                override fun onTrigger(event: TriggerEvent) {
                    record(event.values, -1)
                    sm.requestTriggerSensor(this, sensor) // one-shot sensors must be re-armed
                }
            }
            sm.requestTriggerSensor(trigger, sensor)
            onDispose { sm.cancelTriggerSensor(trigger, sensor) }
        } else {
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) = record(event.values, event.accuracy)
                override fun onAccuracyChanged(s: Sensor?, a: Int) {
                    accuracy = a
                }
            }
            sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
            onDispose { sm.unregisterListener(listener) }
        }
    }

    // Live view of what is stored in the cloud database.
    DisposableEffect(hw.key) {
        val remove = Cloud.listenReadings(hw.key, 8) { cloud = it }
        onDispose { remove() }
    }

    AppScaffold(hw.title, onBack) {
        InfoCard {
            Text(hw.meta.category, style = MaterialTheme.typography.titleMedium)
            Text(hw.meta.description, style = MaterialTheme.typography.bodyMedium)
        }

        if (!granted && permission != null) {
            PermissionCard("This sensor needs permission before it can be used.") { launcher.launch(permission) }
        } else {
            SectionTitle("Live values")
            InfoCard {
                if (values.isEmpty()) {
                    Text(
                        if (hw.oneShot) "Waiting for the trigger event (e.g. start moving the phone)…"
                        else "Waiting for first reading…"
                    )
                }
                values.forEachIndexed { i, v ->
                    val label = hw.meta.axes.getOrNull(i) ?: "Value ${i + 1}"
                    KeyValue(label, fmt(v) + if (hw.meta.unit.isNotEmpty() && i < 3) " ${hw.meta.unit}" else "")
                }
                if (lastTs > 0) {
                    KeyValue("Accuracy", accuracyName(accuracy))
                    KeyValue("Time", timeStr(lastTs))
                    KeyValue("Samples received", samples.toString())
                }
            }
            if (history.size > 1) LineChart(history)

            SectionTitle("Cloud sync")
            InfoCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Store readings in database (1 per second)", Modifier.weight(1f))
                    Switch(checked = syncOn, onCheckedChange = { syncOn = it })
                }
                KeyValue("Uploaded this session", uploaded.toString())
            }
            SectionTitle("Latest records in database (live)")
            InfoCard {
                if (cloud.isEmpty()) Text("No records yet.")
                cloud.forEach { r ->
                    Mono("${timeStr(r.ts)}  " + r.values.take(4).joinToString(", ") { "%.3f".format(it) })
                }
            }
        }

        SectionTitle("Sensor specification")
        InfoCard {
            KeyValue("Name", sensor.name)
            KeyValue("Vendor", sensor.vendor)
            KeyValue("Type", sensor.stringType)
            KeyValue("Version", sensor.version.toString())
            KeyValue("Max range", sensor.maximumRange.toString() + " " + hw.meta.unit)
            KeyValue("Resolution", sensor.resolution.toString())
            KeyValue("Power", "${sensor.power} mA")
            KeyValue("Min delay", "${sensor.minDelay} µs")
            KeyValue("Reporting mode", reportingName(sensor.reportingMode))
            KeyValue("Wake-up sensor", if (sensor.isWakeUpSensor) "Yes" else "No")
        }
    }
}

private fun accuracyName(a: Int) = when (a) {
    SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> "High"
    SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> "Medium"
    SensorManager.SENSOR_STATUS_ACCURACY_LOW -> "Low"
    SensorManager.SENSOR_STATUS_UNRELIABLE -> "Unreliable"
    else -> "n/a"
}

private fun reportingName(m: Int) = when (m) {
    Sensor.REPORTING_MODE_CONTINUOUS -> "Continuous"
    Sensor.REPORTING_MODE_ON_CHANGE -> "On change"
    Sensor.REPORTING_MODE_ONE_SHOT -> "One shot"
    Sensor.REPORTING_MODE_SPECIAL_TRIGGER -> "Special trigger"
    else -> m.toString()
}

/* ------------------------------------------------------------------ */
/*  GPS / Location                                                     */
/* ------------------------------------------------------------------ */

@SuppressLint("MissingPermission")
@Composable
fun LocationScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val perms = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    var granted by remember { mutableStateOf(SensorCatalog.has(ctx, perms[0]) || SensorCatalog.has(ctx, perms[1])) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        granted = res.values.any { it }
    }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(perms) }

    var loc by remember { mutableStateOf<Location?>(null) }
    var provider by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Waiting for location fix…") }
    var uploaded by remember { mutableIntStateOf(0) }
    var cloud by remember { mutableStateOf(listOf<CloudReading>()) }

    DisposableEffect(granted) {
        if (!granted) return@DisposableEffect onDispose { }
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        var lastWrite = 0L
        val listener = object : LocationListener {
            override fun onLocationChanged(l: Location) {
                loc = l
                provider = l.provider ?: ""
                val now = System.currentTimeMillis()
                if (now - lastWrite >= 1000) {
                    lastWrite = now
                    Cloud.writeReading(
                        "gps", "GPS / Location", -1,
                        listOf(
                            l.latitude.toFloat(), l.longitude.toFloat(), l.altitude.toFloat(),
                            l.speed, l.accuracy, l.bearing,
                        ),
                        -1, now,
                    )
                    uploaded += 1
                }
            }

            @Deprecated("Required on older Android versions")
            override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {
            }

            override fun onProviderEnabled(p: String) {}
            override fun onProviderDisabled(p: String) {}
        }
        var any = false
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            try {
                if (lm.isProviderEnabled(p)) {
                    lm.requestLocationUpdates(p, 1000L, 0f, listener)
                    any = true
                }
            } catch (_: Exception) {
            }
        }
        if (!any) status = "Location is switched off. Turn on GPS in the phone settings."
        onDispose { lm.removeUpdates(listener) }
    }

    DisposableEffect(Unit) {
        val remove = Cloud.listenReadings("gps", 8) { cloud = it }
        onDispose { remove() }
    }

    AppScaffold("GPS / Location", onBack) {
        InfoCard {
            Text("Location", style = MaterialTheme.typography.titleMedium)
            Text("Uses GPS and network location to find the device's position on Earth.")
        }
        if (!granted) {
            PermissionCard("Location permission is needed to read GPS data.") { launcher.launch(perms) }
        } else {
            SectionTitle("Live values")
            InfoCard {
                val l = loc
                if (l == null) Text(status) else {
                    KeyValue("Latitude", "%.6f °".format(l.latitude))
                    KeyValue("Longitude", "%.6f °".format(l.longitude))
                    KeyValue("Altitude", "%.1f m".format(l.altitude))
                    KeyValue("Speed", "%.2f m/s".format(l.speed))
                    KeyValue("Bearing", "%.1f °".format(l.bearing))
                    KeyValue("Accuracy", "%.1f m".format(l.accuracy))
                    KeyValue("Provider", provider)
                    KeyValue("Time", timeStr(l.time))
                }
            }
            InfoCard { KeyValue("Uploaded this session", uploaded.toString()) }
            SectionTitle("Latest records in database (live)")
            InfoCard {
                if (cloud.isEmpty()) Text("No records yet.")
                cloud.forEach { r ->
                    Mono("${timeStr(r.ts)}  " + r.values.take(3).joinToString(", ") { "%.5f".format(it) })
                }
            }
        }
    }
}

/* ------------------------------------------------------------------ */
/*  Microphone                                                         */
/* ------------------------------------------------------------------ */

@SuppressLint("MissingPermission")
@Composable
fun MicScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val perm = Manifest.permission.RECORD_AUDIO
    var granted by remember { mutableStateOf(SensorCatalog.has(ctx, perm)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(perm) }

    var running by remember { mutableStateOf(false) }
    var level by remember { mutableFloatStateOf(-90f) }
    var peak by remember { mutableFloatStateOf(-90f) }
    var levels by remember { mutableStateOf(listOf<Float>()) }
    var error by remember { mutableStateOf<String?>(null) }
    var uploaded by remember { mutableIntStateOf(0) }
    var cloud by remember { mutableStateOf(listOf<CloudReading>()) }

    LaunchedEffect(running, granted) {
        if (!running || !granted) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            val rate = 44100
            val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val rec = AudioRecord(
                MediaRecorder.AudioSource.MIC, rate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(min, 4096) * 2,
            )
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                error = "Could not open the microphone."
                running = false
                rec.release()
                return@withContext
            }
            error = null
            val buf = ShortArray(max(min, 4096))
            var lastWrite = 0L
            try {
                rec.startRecording()
                while (isActive) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    var sum = 0.0
                    var pk = 0
                    for (i in 0 until n) {
                        val s = buf[i].toInt()
                        sum += s.toDouble() * s
                        pk = max(pk, abs(s))
                    }
                    val rms = sqrt(sum / n)
                    val db = (20 * log10(max(rms, 1.0) / 32768.0)).toFloat() // dB full scale (<= 0)
                    val pkDb = (20 * log10(max(pk, 1) / 32768.0)).toFloat()
                    level = db
                    peak = pkDb
                    levels = (levels + db).takeLast(MAX_HISTORY)
                    val now = System.currentTimeMillis()
                    if (now - lastWrite >= 1000) {
                        lastWrite = now
                        Cloud.writeReading("microphone", "Microphone level", -2, listOf(db, pkDb), -1, now)
                        uploaded += 1
                    }
                }
            } finally {
                try {
                    rec.stop()
                } catch (_: Exception) {
                }
                rec.release()
            }
        }
    }

    DisposableEffect(Unit) {
        val remove = Cloud.listenReadings("microphone", 8) { cloud = it }
        onDispose { remove() }
    }

    AppScaffold("Microphone", onBack) {
        InfoCard {
            Text("Microphone", style = MaterialTheme.typography.titleMedium)
            Text("Measures the loudness of surrounding sound. Only the level (dB) is stored, never the audio itself.")
        }
        if (!granted) {
            PermissionCard("Microphone permission is needed to measure sound level.") { launcher.launch(perm) }
        } else {
            Button(onClick = { running = !running }) { Text(if (running) "Stop" else "Start measuring") }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            SectionTitle("Live values")
            InfoCard {
                KeyValue("Level (RMS)", "%.1f dBFS".format(level))
                KeyValue("Peak", "%.1f dBFS".format(peak))
                KeyValue("Uploaded this session", uploaded.toString())
            }
            if (levels.size > 1) LineChart(levels.map { listOf(it) })
            SectionTitle("Latest records in database (live)")
            InfoCard {
                if (cloud.isEmpty()) Text("No records yet.")
                cloud.forEach { r ->
                    Mono("${timeStr(r.ts)}  " + r.values.take(2).joinToString(", ") { "%.1f".format(it) })
                }
            }
        }
    }
}
