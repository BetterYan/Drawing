package com.zucky.drawing.lineart

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 共用图像预处理：降采样、灰度转换、高斯模糊、双边滤波等。
 * 所有线稿引擎共享这些基础操作。
 */
object ImagePreprocessor {

    /**
     * 等比降采样，使最长边不超过 maxDim。
     */
    fun downsample(input: Bitmap, maxDim: Int): Bitmap {
        val w = input.width
        val h = input.height
        if (w <= maxDim && h <= maxDim) return input

        val scale = maxDim.toFloat() / max(w, h)
        val newW = (w * scale).toInt().coerceAtLeast(1)
        val newH = (h * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(input, newW, newH, true)
    }

    /**
     * 转换为灰度 Bitmap（ARGB_8888，灰度值填入 R=G=B）。
     */
    fun toGrayscale(input: Bitmap): Bitmap {
        val w = input.width
        val h = input.height
        val pixels = IntArray(w * h)
        input.getPixels(pixels, 0, w, 0, 0, w, h)

        val gray = IntArray(w * h)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            // ITU-R BT.601 亮度
            val lum = (r * 299 + g * 587 + b * 114) / 1000
            gray[i] = Color.argb(255, lum, lum, lum)
        }

        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(gray, 0, w, 0, 0, w, h)
        return result
    }

    /**
     * 灰度值数组提取（返回 [0..255] 的 IntArray）。
     */
    fun extractGrayValues(input: Bitmap): IntArray {
        val w = input.width
        val h = input.height
        val pixels = IntArray(w * h)
        input.getPixels(pixels, 0, w, 0, 0, w, h)

        val gray = IntArray(w * h)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            gray[i] = (r * 299 + g * 587 + b * 114) / 1000
        }
        return gray
    }

    /**
     * 高斯模糊（可分离卷积，O(w*h*kernelSize)）。
     * @param gray 灰度值数组
     * @param w 宽度
     * @param h 高度
     * @param sigma 标准差
     * @return 模糊后的灰度值数组
     */
    fun gaussianBlur(gray: IntArray, w: Int, h: Int, sigma: Double): IntArray {
        val kernel = createGaussianKernel(sigma)
        val radius = kernel.size / 2

        // 水平方向
        val horizontal = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var sum = 0.0
                var weightSum = 0.0
                for (k in -radius..radius) {
                    val nx = (x + k).coerceIn(0, w - 1)
                    val weight = kernel[k + radius]
                    sum += gray[y * w + nx] * weight
                    weightSum += weight
                }
                horizontal[y * w + x] = (sum / weightSum).toInt().coerceIn(0, 255)
            }
        }

        // 垂直方向
        val result = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var sum = 0.0
                var weightSum = 0.0
                for (k in -radius..radius) {
                    val ny = (y + k).coerceIn(0, h - 1)
                    val weight = kernel[k + radius]
                    sum += horizontal[ny * w + x] * weight
                    weightSum += weight
                }
                result[y * w + x] = (sum / weightSum).toInt().coerceIn(0, 255)
            }
        }
        return result
    }

    /**
     * 双边滤波（边缘保持平滑）。
     * 用于 XDoG 之前的预处理，去除纹理噪声同时保留边缘。
     * @param gray 灰度值数组
     * @param w 宽度
     * @param h 高度
     * @param spatialSigma 空间域标准差
     * @param rangeSigma 灰度域标准差
     * @param radius 滤波半径
     * @return 滤波后的灰度值数组
     */
    fun bilateralFilter(
        gray: IntArray, w: Int, h: Int,
        spatialSigma: Double = 3.0,
        rangeSigma: Double = 30.0,
        radius: Int = 5
    ): IntArray {
        val result = IntArray(w * h)
        val spatialCoeff = -0.5 / (spatialSigma * spatialSigma)
        val rangeCoeff = -0.5 / (rangeSigma * rangeSigma)

        // 预计算空间权重
        val spatialWeights = DoubleArray((2 * radius + 1) * (2 * radius + 1))
        for (dy in -radius..radius) {
            for (dx in -radius..radius) {
                val dist2 = (dx * dx + dy * dy).toDouble()
                spatialWeights[(dy + radius) * (2 * radius + 1) + (dx + radius)] =
                    kotlin.math.exp(dist2 * spatialCoeff)
            }
        }

        for (y in 0 until h) {
            for (x in 0 until w) {
                val centerIdx = y * w + x
                val centerVal = gray[centerIdx]
                var sum = 0.0
                var weightSum = 0.0

                for (dy in -radius..radius) {
                    val ny = (y + dy).coerceIn(0, h - 1)
                    for (dx in -radius..radius) {
                        val nx = (x + dx).coerceIn(0, w - 1)
                        val neighborVal = gray[ny * w + nx]

                        val spatialW = spatialWeights[(dy + radius) * (2 * radius + 1) + (dx + radius)]
                        val rangeDiff = (centerVal - neighborVal).toDouble()
                        val rangeW = kotlin.math.exp(rangeDiff * rangeDiff * rangeCoeff)

                        val weight = spatialW * rangeW
                        sum += neighborVal * weight
                        weightSum += weight
                    }
                }

                result[centerIdx] = (sum / weightSum).toInt().coerceIn(0, 255)
            }
        }
        return result
    }

    /**
     * 自动对比度增强（直方图拉伸）。
     */
    fun autoContrast(gray: IntArray): IntArray {
        var minVal = 255
        var maxVal = 0
        for (v in gray) {
            if (v < minVal) minVal = v
            if (v > maxVal) maxVal = v
        }

        val range = maxVal - minVal
        if (range < 10) return gray // 对比度已经很低，跳过

        val result = IntArray(gray.size)
        for (i in gray.indices) {
            result[i] = ((gray[i] - minVal) * 255 / range).coerceIn(0, 255)
        }
        return result
    }

    /**
     * 创建一维高斯核。
     */
    private fun createGaussianKernel(sigma: Double): DoubleArray {
        val radius = (sigma * 3).toInt().coerceAtLeast(1)
        val size = radius * 2 + 1
        val kernel = DoubleArray(size)
        val coeff = -0.5 / (sigma * sigma)
        var sum = 0.0

        for (i in 0 until size) {
            val x = (i - radius).toDouble()
            kernel[i] = kotlin.math.exp(x * x * coeff)
            sum += kernel[i]
        }

        // 归一化
        for (i in kernel.indices) {
            kernel[i] /= sum
        }
        return kernel
    }
}
