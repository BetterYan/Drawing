package com.zucky.drawing.photo

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import com.zucky.drawing.R
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.core.content.FileProvider

/**
 * 照片选择 & 相机拍摄封装。
 * 提供统一的 API 来获取照片 URI。
 */
class PhotoPickerHelper(private val activity: ComponentActivity) {

    /** 回调：用户选中了照片 URI */
    var onPhotoSelected: ((Uri) -> Unit)? = null

    /** 相机拍摄临时文件 URI */
    var cameraPhotoUri: Uri? = null
        private set

    /** 相册选择 launcher */
    val pickImageLauncher: ActivityResultLauncher<PickVisualMediaRequest> =
        activity.registerForActivityResult(
            ActivityResultContracts.PickVisualMedia()
        ) { uri: Uri? ->
            if (uri != null) {
                onPhotoSelected?.invoke(uri)
            }
        }

    /** 相机拍摄 launcher */
    private val takePictureLauncher: ActivityResultLauncher<Uri> =
        activity.registerForActivityResult(
            ActivityResultContracts.TakePicture()
        ) { success: Boolean ->
            if (success && cameraPhotoUri != null) {
                onPhotoSelected?.invoke(cameraPhotoUri!!)
            } else {
                // 拍摄取消或失败，清理临时文件
                cameraPhotoUri?.let { uri ->
                    try {
                        activity.contentResolver.delete(uri, null, null)
                    } catch (_: Exception) { /* ignore */ }
                }
                cameraPhotoUri = null
            }
        }

    /**
     * 启动相册选择。
     */
    fun pickFromGallery() {
        pickImageLauncher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        )
    }

    /**
     * 启动相机拍摄。
     * @return 临时照片文件 URI
     */
    @Throws(IOException::class)
    fun takePhoto(): Uri {
        val photoFile = createImageFile()
        val uri = FileProvider.getUriForFile(
            activity,
            "${activity.packageName}.fileprovider",
            photoFile
        )
        cameraPhotoUri = uri
        takePictureLauncher.launch(uri)
        return uri
    }

    /**
     * 显示照片来源选择对话框（相册 / 相机）。
     */
    fun showSourceDialog() {
        val options = arrayOf(
            activity.getString(R.string.photo_source_gallery),
            activity.getString(R.string.photo_source_camera)
        )
        AlertDialog.Builder(activity)
            .setTitle(R.string.photo_source_title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> pickFromGallery()
                    1 -> {
                        try {
                            takePhoto()
                        } catch (e: IOException) {
                            AlertDialog.Builder(activity)
                                .setMessage(R.string.photo_camera_error)
                                .setPositiveButton(android.R.string.ok, null)
                                .show()
                        }
                    }
                }
            }
            .show()
    }

    /**
     * 创建临时照片文件。
     */
    private fun createImageFile(): File {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val imageFileName = "DRAW_${timeStamp}_"
        val storageDir = activity.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
            ?: activity.cacheDir  // fallback to internal cache if external storage unavailable
        return File.createTempFile(imageFileName, ".jpg", storageDir)
    }

    /**
     * 持久化 URI 读权限（Photo Picker 返回的 URI 默认权限到进程结束）。
     */
    fun persistUriPermission(uri: Uri) {
        try {
            activity.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: SecurityException) {
            // FileProvider URI 不支持持久化，intent grant 已足够
        }
    }
}
