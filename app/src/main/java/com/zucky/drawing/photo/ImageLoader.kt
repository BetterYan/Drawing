package com.zucky.drawing.photo

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import kotlin.math.min

/**
 * 高效图像加载器，避免 OOM。
 * 使用 ImageDecoder (API 28+) 或 BitmapFactory 进行降采样加载。
 */
object ImageLoader {

    /**
     * 从 URI 加载 Bitmap，自动降采样到目标尺寸。
     * @param resolver ContentResolver
     * @param uri 图像 URI
     * @param targetWidth 目标宽度
     * @param targetHeight 目标高度
     * @return 降采样后的 Bitmap，加载失败返回 null
     */
    fun decodeBitmapFromUri(
        resolver: ContentResolver,
        uri: Uri,
        targetWidth: Int,
        targetHeight: Int
    ): Bitmap? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                decodeWithImageDecoder(resolver, uri, targetWidth, targetHeight)
            } else {
                decodeWithBitmapFactory(resolver, uri, targetWidth, targetHeight)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * 使用 ImageDecoder (API 28+) 加载。
     * 支持自动降采样和内存优化。
     */
    private fun decodeWithImageDecoder(
        resolver: ContentResolver,
        uri: Uri,
        targetWidth: Int,
        targetHeight: Int
    ): Bitmap? {
        val source = ImageDecoder.createSource(resolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val srcW = info.size.width
            val srcH = info.size.height
            val scale = min(
                targetWidth.toFloat() / srcW,
                targetHeight.toFloat() / srcH
            )
            if (scale < 1f) {
                decoder.setTargetSize(
                    (srcW * scale).toInt().coerceAtLeast(1),
                    (srcH * scale).toInt().coerceAtLeast(1)
                )
            }
            decoder.memorySizePolicy = ImageDecoder.MEMORY_POLICY_LOW_RAM
        }
    }

    /**
     * 使用 BitmapFactory 加载（向后兼容）。
     */
    private fun decodeWithBitmapFactory(
        resolver: ContentResolver,
        uri: Uri,
        targetWidth: Int,
        targetHeight: Int
    ): Bitmap? {
        // 第一步：仅解码尺寸
        val options = android.graphics.BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        resolver.openInputStream(uri)?.use { stream ->
            android.graphics.BitmapFactory.decodeStream(stream, null, options)
        }

        val srcW = options.outWidth
        val srcH = options.outHeight
        if (srcW <= 0 || srcH <= 0) return null

        // 计算 inSampleSize（必须是 2 的幂）
        val sampleSize = calculateInSampleSize(srcW, srcH, targetWidth, targetHeight)

        // 第二步：降采样解码
        val decodeOptions = android.graphics.BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return resolver.openInputStream(uri)?.use { stream ->
            android.graphics.BitmapFactory.decodeStream(stream, null, decodeOptions)
        }
    }

    /**
     * 计算最优 inSampleSize（2 的幂）。
     */
    private fun calculateInSampleSize(
        srcWidth: Int, srcHeight: Int,
        targetWidth: Int, targetHeight: Int
    ): Int {
        var sampleSize = 1
        if (srcHeight > targetHeight || srcWidth > targetWidth) {
            var halfH = srcHeight / 2
            var halfW = srcWidth / 2
            while ((halfH / sampleSize) >= targetHeight &&
                (halfW / sampleSize) >= targetWidth) {
                sampleSize *= 2
            }
        }
        return sampleSize
    }

    /**
     * 获取图像尺寸（不加载像素）。
     */
    fun getImageSize(resolver: ContentResolver, uri: Uri): Pair<Int, Int>? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val source = ImageDecoder.createSource(resolver, uri)
                var size: Pair<Int, Int>? = null
                ImageDecoder.decodeBitmap(source) { _, info, _ ->
                    size = Pair(info.size.width, info.size.height)
                    // 抛出异常中断解码（仅获取尺寸）
                    throw ImageDecoderDecodeInterrupted()
                }
                null // unreachable
            } else {
                val options = android.graphics.BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                resolver.openInputStream(uri)?.use { stream ->
                    android.graphics.BitmapFactory.decodeStream(stream, null, options)
                }
                Pair(options.outWidth, options.outHeight)
            }
        } catch (e: ImageDecoderDecodeInterrupted) {
            // 通过异常中断获取到了 size，但这里无法返回
            null
        } catch (e: Exception) {
            null
        }
    }

    /** 用于中断 ImageDecoder 解码的内部异常 */
    private class ImageDecoderDecodeInterrupted : RuntimeException()
}
