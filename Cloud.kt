package com.sensorhub.app.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Base64
import androidx.exifinterface.media.ExifInterface
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

data class CloudReading(val ts: Long, val values: List<Double>)
data class CloudPhoto(val ts: Long, val imageBase64: String)

/**
 * Firebase Realtime Database layout (everything is private to the signed-in user):
 *
 * users/{uid}/profile                         email, lastLogin
 * users/{uid}/sensors/{sensorKey}/info        name, type
 * users/{uid}/sensors/{sensorKey}/latest      newest reading
 * users/{uid}/sensors/{sensorKey}/readings/*  every stored reading (ts, time, values[], accuracy)
 * users/{uid}/sensors/gps | microphone        same layout as other sensors
 * users/{uid}/camera/cam_{id}/info            cameraId, facing
 * users/{uid}/camera/cam_{id}/photos/*        ts, time, file, imageBase64 (compressed JPEG)
 */
object Cloud {
    private val db: FirebaseDatabase get() = FirebaseDatabase.getInstance()
    val uid: String? get() = FirebaseAuth.getInstance().currentUser?.uid
    private val infoWritten = HashSet<String>()

    private fun base(path: String): DatabaseReference? = uid?.let { db.getReference("users/$it/$path") }

    private fun iso(ts: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(Date(ts))

    private fun clean(values: List<Float>): List<Double> =
        values.map { if (it.isFinite()) it.toDouble() else 0.0 }

    fun saveProfile() {
        val user = FirebaseAuth.getInstance().currentUser ?: return
        base("profile")?.setValue(
            mapOf<String, Any>(
                "email" to (user.email ?: ""),
                "lastLogin" to ServerValue.TIMESTAMP,
            )
        )
    }

    fun writeReading(key: String, name: String, type: Int, values: List<Float>, accuracy: Int, ts: Long) {
        val ref = base("sensors/$key") ?: return
        val rec = mapOf<String, Any>(
            "ts" to ts,
            "time" to iso(ts),
            "values" to clean(values),
            "accuracy" to accuracy,
            "serverTs" to ServerValue.TIMESTAMP,
        )
        if (infoWritten.add(key)) {
            ref.child("info").setValue(mapOf<String, Any>("name" to name, "type" to type))
        }
        ref.child("latest").setValue(rec)
        ref.child("readings").push().setValue(rec)
    }

    fun listenReadings(key: String, limit: Int, cb: (List<CloudReading>) -> Unit): () -> Unit {
        val ref = base("sensors/$key/readings") ?: return {}
        val q = ref.limitToLast(limit)
        val l = object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                val list = s.children.mapNotNull { c ->
                    val ts = c.child("ts").getValue(Long::class.java) ?: return@mapNotNull null
                    val vals = c.child("values").children.mapNotNull { it.getValue(Double::class.java) }
                    CloudReading(ts, vals)
                }.reversed()
                cb(list)
            }

            override fun onCancelled(e: DatabaseError) {}
        }
        q.addValueEventListener(l)
        return { q.removeEventListener(l) }
    }

    fun uploadPhoto(camId: String, facing: String, file: File, ts: Long) {
        val ref = base("camera/cam_$camId") ?: return
        Thread {
            val b64 = ImageUtil.thumbnailBase64(file) ?: return@Thread
            ref.child("info").setValue(mapOf<String, Any>("cameraId" to camId, "facing" to facing))
            ref.child("photos").push().setValue(
                mapOf<String, Any>(
                    "ts" to ts,
                    "time" to iso(ts),
                    "file" to file.name,
                    "imageBase64" to b64,
                    "serverTs" to ServerValue.TIMESTAMP,
                )
            )
        }.start()
    }

    fun listenPhotos(camId: String, limit: Int, cb: (List<CloudPhoto>) -> Unit): () -> Unit {
        val ref = base("camera/cam_$camId/photos") ?: return {}
        val q = ref.limitToLast(limit)
        val l = object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                val list = s.children.mapNotNull { c ->
                    val ts = c.child("ts").getValue(Long::class.java) ?: return@mapNotNull null
                    val b64 = c.child("imageBase64").getValue(String::class.java) ?: return@mapNotNull null
                    CloudPhoto(ts, b64)
                }.reversed()
                cb(list)
            }

            override fun onCancelled(e: DatabaseError) {}
        }
        q.addValueEventListener(l)
        return { q.removeEventListener(l) }
    }

    fun listenConnected(cb: (Boolean) -> Unit): () -> Unit {
        val ref = db.getReference(".info/connected")
        val l = object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                cb(s.getValue(Boolean::class.java) == true)
            }

            override fun onCancelled(e: DatabaseError) {}
        }
        ref.addValueEventListener(l)
        return { ref.removeEventListener(l) }
    }
}

object ImageUtil {
    /** Downscaled, rotation-corrected JPEG as Base64 (small enough to live in the Realtime Database). */
    fun thumbnailBase64(file: File, maxSide: Int = 480, quality: Int = 70): String? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / sample > maxSide * 2) sample *= 2
            val bmp = BitmapFactory.decodeFile(
                file.path, BitmapFactory.Options().apply { inSampleSize = sample }
            ) ?: return null
            val rotation = when (
                ExifInterface(file.path).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
                )
            ) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
            val scale = maxSide.toFloat() / max(bmp.width, bmp.height)
            val matrix = Matrix().apply {
                postRotate(rotation)
                if (scale < 1f) postScale(scale, scale)
            }
            val out = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
            val bos = ByteArrayOutputStream()
            out.compress(Bitmap.CompressFormat.JPEG, quality, bos)
            Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
        } catch (e: Exception) {
            null
        }
    }

    fun decode(b64: String): Bitmap? = try {
        val bytes = Base64.decode(b64, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    } catch (e: Exception) {
        null
    }
}
