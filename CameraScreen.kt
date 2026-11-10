package com.sensorhub.app.ui

import android.Manifest
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.sensorhub.app.data.Cloud
import com.sensorhub.app.data.CloudPhoto
import com.sensorhub.app.data.ImageUtil
import com.sensorhub.app.data.SensorCatalog
import java.io.File

private data class CamInfo(val id: String, val label: String)

private fun listCameras(ctx: Context): List<CamInfo> = try {
    val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    cm.cameraIdList.map { id ->
        val facing = when (cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)) {
            CameraCharacteristics.LENS_FACING_FRONT -> "Front"
            CameraCharacteristics.LENS_FACING_BACK -> "Back"
            CameraCharacteristics.LENS_FACING_EXTERNAL -> "External"
            else -> "Camera"
        }
        CamInfo(id, "$facing (id $id)")
    }
} catch (e: Exception) {
    emptyList()
}

@OptIn(ExperimentalCamera2Interop::class)
private fun selectorFor(id: String): CameraSelector =
    CameraSelector.Builder()
        .addCameraFilter { infos -> infos.filter { Camera2CameraInfo.from(it).cameraId == id } }
        .build()

@Composable
fun CameraScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val perm = Manifest.permission.CAMERA
    var granted by remember { mutableStateOf(SensorCatalog.has(ctx, perm)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(perm) }

    val cams = remember(granted) { if (granted) listCameras(ctx) else emptyList() }
    var selectedId by remember(cams) { mutableStateOf(cams.firstOrNull()?.id) }
    var status by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var photos by remember { mutableStateOf(listOf<CloudPhoto>()) }

    val previewView = remember { PreviewView(ctx) }
    val imageCapture = remember(selectedId) {
        ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
    }

    DisposableEffect(selectedId, granted) {
        var provider: ProcessCameraProvider? = null
        val id = selectedId
        if (granted && id != null) {
            val future = ProcessCameraProvider.getInstance(ctx)
            future.addListener({
                try {
                    val p = future.get()
                    provider = p
                    p.unbindAll()
                    val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                    p.bindToLifecycle(owner, selectorFor(id), preview, imageCapture)
                    error = null
                } catch (e: Exception) {
                    error = "This camera cannot be opened on its own: ${e.message ?: "unsupported"}"
                }
            }, ContextCompat.getMainExecutor(ctx))
        }
        onDispose { provider?.unbindAll() }
    }

    DisposableEffect(selectedId) {
        val id = selectedId
        val remove = if (id != null) Cloud.listenPhotos(id, 12) { photos = it } else ({})
        onDispose { remove() }
    }

    AppScaffold("Camera", onBack) {
        if (!granted) {
            PermissionCard("Camera permission is needed to take and store photos.") { launcher.launch(perm) }
            return@AppScaffold
        }
        if (cams.isEmpty()) {
            Text("No camera found on this device.")
            return@AppScaffold
        }

        Text(
            "Each camera stores its photos separately: full-size on the phone and a compressed copy in the cloud database.",
            style = MaterialTheme.typography.bodyMedium,
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(cams) { c ->
                FilterChip(
                    selected = c.id == selectedId,
                    onClick = { selectedId = c.id },
                    label = { Text(c.label) },
                )
            }
        }

        AndroidView(
            factory = {
                (previewView.parent as? android.view.ViewGroup)?.removeView(previewView)
                previewView
            },
            modifier = Modifier.fillMaxWidth().height(340.dp).clip(MaterialTheme.shapes.medium),
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        Button(
            modifier = Modifier.fillMaxWidth(),
            enabled = selectedId != null && error == null,
            onClick = {
                val id = selectedId ?: return@Button
                val label = cams.firstOrNull { it.id == id }?.label ?: id
                val ts = System.currentTimeMillis()
                val file = File(ctx.filesDir, "camera/cam_$id/IMG_$ts.jpg").apply { parentFile?.mkdirs() }
                status = "Capturing…"
                imageCapture.takePicture(
                    ImageCapture.OutputFileOptions.Builder(file).build(),
                    ContextCompat.getMainExecutor(ctx),
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                            status = "Saved on phone · uploading to database…"
                            Cloud.uploadPhoto(id, label, file, ts)
                        }

                        override fun onError(exception: ImageCaptureException) {
                            status = "Capture failed: ${exception.message}"
                        }
                    },
                )
            },
        ) { Text("Capture & store photo") }
        if (status.isNotEmpty()) Text(status)

        SectionTitle("Stored photos for this camera (live from database)")
        if (photos.isEmpty()) Text("No photos yet.")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(photos) { p ->
                val bmp = remember(p.ts) { ImageUtil.decode(p.imageBase64) }
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = timeStr(p.ts),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(110.dp).clip(MaterialTheme.shapes.small),
                    )
                }
            }
        }
    }
}
