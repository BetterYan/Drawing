package com.zucky.drawing.lineart

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.tanh

/**
 * XDoG (eXtended Difference of Gaussians) 线稿引擎。
 *
 * 基于 Winnemöller (2012) 论文实现。
 * 管线：双边滤波 → 灰度 → 高斯模糊(×2) → DoG → 阈值化(tanh) → 形态学修复 → 白底黑线
 *
 * 参数说明：
 * - sigma: 基础高斯标准差（控制空间尺度，0.5~2.0）
 * - k: 两个高斯的比率（1.6 为推荐值）
 * - epsilon: 阈值（15~65，越高线条越少）
 * - phi: tanh 陡度（10~30，控制线条粗细和色调响应）
 */
class XDoGLineArtEngine : LineArtEngine {

    override val name: String = "XDoG"

    // ── 参数配置（不可变数据类，线程安全）──
    private data class XDoGParams(
        val sigma: Double,
        val k: Double,
        val epsilon: Double,
        val phi: Double,
        val bilateralPasses: Int,
        val bilateralRadius: Int,
        val bilateralSpatialSigma: Double,
        val bilateralRangeSigma: Double,
        val morphCloseRadius: Int,
        val minComponentArea: Int,
        val workingMaxDim: Int
    )

    /**
     * 根据 detailLevel (0~1) 计算参数。
     * 0 = 粗线条（少量轮廓），1 = 细线条（大量细节）。
     */
    private fun computeParams(level: Float): XDoGParams {
        val l = level.coerceIn(0f, 1f)
        return XDoGParams(
            sigma = 1.2 - l * 0.6,           // 1.2 → 0.6
            k = 1.6,
            epsilon = 0.06 - l * 0.055,       // 0.06 → 0.005
            phi = 20.0 - l * 10.0,            // 20 → 10
            bilateralPasses = (4 - l * 2).toInt().coerceIn(1, 5),
            bilateralRadius = 5,
            bilateralSpatialSigma = 3.0,
            bilateralRangeSigma = 30.0,
            morphCloseRadius = 1,
            minComponentArea = (50 - l * 40).toInt().coerceIn(5, 60),
            workingMaxDim = 768
        )
    }

    override suspend fun convert(
        inputBitmap: Bitmap,
        detailLevel: Float,
        onProgress: ((Float) -> Unit)?
    ): Bitmap {
        require(!inputBitmap.isRecycled) { "Input bitmap is recycled" }
        require(inputBitmap.width > 0 && inputBitmap.height > 0) { "Input bitmap has zero dimensions" }

        val params = computeParams(detailLevel)
        onProgress?.invoke(0.05f)

        // ── Step 1: 降采样到工作分辨率 ──
        val working = ImagePreprocessor.downsample(inputBitmap, params.workingMaxDim)
        val w = working.width
        val h = working.height
        onProgress?.invoke(0.1f)

        // ── Step 2: 提取灰度值 ──
        var gray = ImagePreprocessor.extractGrayValues(working)
        if (working !== inputBitmap) working.recycle()
        onProgress?.invoke(0.15f)

        // ── Step 3: 自动对比度增强 ──
        gray = ImagePreprocessor.autoContrast(gray)
        onProgress?.invoke(0.2f)

        // ── Step 4: 双边滤波（多次迭代，边缘保持平滑）──
        for (i in 0 until params.bilateralPasses) {
            gray = ImagePreprocessor.bilateralFilter(
                gray, w, h,
                spatialSigma = params.bilateralSpatialSigma,
                rangeSigma = params.bilateralRangeSigma,
                radius = params.bilateralRadius
            )
            val progress = 0.2f + 0.35f * (i + 1) / params.bilateralPasses
            onProgress?.invoke(progress)
        }

        // ── Step 5: XDoG 边缘检测 ──
        val dog = computeXDoG(gray, w, h, params.sigma, params.k)
        onProgress?.invoke(0.65f)

        // ── Step 6: 阈值化（tanh 平滑阈值）──
        val edgeMask = thresholdXDoG(dog, w * h, params.epsilon, params.phi)
        onProgress?.invoke(0.7f)

        // ── Step 7: 形态学闭运算（修复断线）──
        val closed = LineArtPostprocessor.morphologicalClose(
            edgeMask, w, h, params.morphCloseRadius
        )
        onProgress?.invoke(0.75f)

        // ── Step 8: 形态学开运算（去噪点）──
        val opened = LineArtPostprocessor.morphologicalOpen(
            closed, w, h, 1
        )
        onProgress?.invoke(0.8f)

        // ── Step 9: 去除小碎片 ──
        val cleaned = LineArtPostprocessor.removeSmallComponents(
            opened, w, h, params.minComponentArea
        )
        onProgress?.invoke(0.85f)

        // ── Step 10: 生成白底黑线 Bitmap ──
        val lineArt = LineArtPostprocessor.toWhiteBlackBitmap(cleaned, w, h)
        onProgress?.invoke(0.9f)

        // ── Step 11: 升采样回原始分辨率 ──
        val result = LineArtPostprocessor.upscale(
            lineArt, inputBitmap.width, inputBitmap.height
        )
        if (result !== lineArt) lineArt.recycle()
        onProgress?.invoke(1.0f)

        return result
    }

    /**
     * 计算 XDoG (eXtended Difference of Gaussians)。
     *
     * 公式：
     *   G1 = gray * Gaussian(sigma)
     *   G2 = gray * Gaussian(sigma * k)
     *   DoG = G1 - p * G2  （p 为权重因子，此处简化为 1.0）
     *
     * @return DoubleArray 每个像素的 DoG 响应值（归一化到 [0, 1]）
     */
    private fun computeXDoG(
        gray: IntArray, w: Int, h: Int,
        sigma: Double, k: Double
    ): DoubleArray {
        // 两个高斯模糊
        val g1 = ImagePreprocessor.gaussianBlur(gray, w, h, sigma)
        val g2 = ImagePreprocessor.gaussianBlur(gray, w, h, sigma * k)

        val total = w * h
        val dog = DoubleArray(total)
        var minVal = Double.MAX_VALUE
        var maxVal = -Double.MAX_VALUE

        // 计算 DoG
        val p = 0.988  // 权重因子，接近1但略小于1，增强边缘对比
        for (i in 0 until total) {
            dog[i] = g1[i].toDouble() - p * g2[i].toDouble()
            if (dog[i] < minVal) minVal = dog[i]
            if (dog[i] > maxVal) maxVal = dog[i]
        }

        // 归一化到 [0, 1]
        val range = maxVal - minVal
        if (range > 0.001) {
            for (i in dog.indices) {
                dog[i] = (dog[i] - minVal) / range
            }
        }

        return dog
    }

    /**
     * XDoG 阈值化（平滑 tanh 过渡）。
     *
     * 公式：
     *   T(D) = 1.0                    if D >= epsilon
     *   T(D) = 1.0 + tanh(phi * D)    if D < epsilon
     *
     * 结果 > 0.5 → 背景（白色）
     * 结果 <= 0.5 → 边缘（黑色）
     *
     * @return BooleanArray true = 边缘（前景），false = 背景
     */
    private fun thresholdXDoG(
        dog: DoubleArray, total: Int,
        epsilon: Double, phi: Double
    ): BooleanArray {
        val mask = BooleanArray(total)
        for (i in 0 until total) {
            val d = dog[i]
            val t = if (d >= epsilon) {
                1.0
            } else {
                1.0 + tanh(phi * d)
            }
            // t > 0.5 → 背景 (false), t <= 0.5 → 边缘 (true)
            mask[i] = t <= 0.5
        }
        return mask
    }
}
