package com.zucky.drawing.lineart

import android.graphics.Bitmap

/**
 * 线稿转换引擎统一接口。
 * 所有实现（ML / 经典 CV）都通过此接口调用。
 */
interface LineArtEngine {
    /** 引擎名称（用于 UI 显示和调试） */
    val name: String

    /**
     * 将彩色照片转换为白底黑线线稿。
     * @param inputBitmap 输入彩色照片 Bitmap
     * @param detailLevel 细节级别 0.0~1.0（0=最少细节，1=最多细节）
     * @param onProgress 进度回调 0.0~1.0
     * @return 白底黑线线稿 Bitmap
     */
    suspend fun convert(
        inputBitmap: Bitmap,
        detailLevel: Float = 0.5f,
        onProgress: ((Float) -> Unit)? = null
    ): Bitmap

    /**
     * 检查引擎是否可用（如模型文件是否存在）。
     */
    fun isAvailable(): Boolean = true
}
