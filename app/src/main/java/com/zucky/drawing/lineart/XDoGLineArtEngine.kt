package com.zucky.drawing.lineart

import android.graphics.Bitmap
import android.graphics.Canvas
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.max

/**
 * 基于 OpenCV Mat 的 XDoG（扩展高斯差分）线稿提取引擎。
 *
 * 算法管线：
 * 1. Bitmap → Mat（确保 ARGB_8888）
 * 2. resize(INTER_AREA 降采样)
 * 3. cvtColor(RGB2GRAY) → CV_8U
 * 4. convertTo(CV_64F, 1/255) 归一化到 [0,1]
 * 5. GaussianBlur(σ) → gauss1
 * 6. GaussianBlur(k·σ) → gauss2
 * 7. DoG = gauss1 - p * gauss2
 * 8. tanh 阈值化：T(D) = 1 + tanh(φ·(D - ε))
 * 9. edgeMask = T(D) <= 0.5（黑线为边缘）
 * 10. convertTo(CV_8U, 255) → 二值化边缘图
 * 11. morphologyEx(CLOSE) 修复断线
 * 12. morphologyEx(OPEN) 去噪
 * 13. removeSmallComponents 去除小连通域
 * 14. threshold 确保纯黑白
 * 15. bitwise_not 反色 → 白底黑线
 * 16. resize(INTER_CUBIC 升采样) → Bitmap
 */
class XDoGLineArtEngine : LineArtEngine {

    override val name: String = "XDoG"

    companion object {
        private const val WORKING_MAX_DIM = 768

        /** 必须先调用 OpenCVLoader.initLocal() */
        fun isOpenCvInitialized(): Boolean = OpenCVLoader.initLocal()
    }

    /**
     * XDoG 参数封装。
     */
    private data class XDoGParams(
        val sigma: Double,          // 高斯核主尺度
        val k: Double,              // 尺度比（固定 2.0）
        val p: Double,              // DoG 权重因子
        val phi: Double,            // tanh 锐度
        val epsilon: Double,        // tanh 阈值偏移
        val morphCloseSize: Int,    // 闭运算核大小
        val morphOpenSize: Int,     // 开运算核大小
        val minComponentArea: Int   // 最小连通域面积
    )

    /**
     * 根据 detailLevel (0~1) 计算 XDoG 参数。
     * 0 = 粗线条/少细节，1 = 细线条/多细节。
     *
     * 经典 XDoG 参数参考（Winnemöller, 2012）：
     * - σ: 0.5~3.0  控制线条尺度
     * - k: 1.6      经典尺度比
     * - p: ~0.98    必须接近 1（DoG = G(σ) - p·G(kσ)）
     * - φ: 10~30    tanh 锐度
     * - ε: 0.01~0.1 阈值偏移
     */
    private fun computeParams(level: Float): XDoGParams {
        val l = level.coerceIn(0f, 1f)
        return XDoGParams(
            // σ：大 = 粗线条/少细节，小 = 细线条/多细节
            sigma = (2.0 - l * 1.5).coerceIn(0.5, 2.0),
            // k：经典尺度比 1.6
            k = 1.6,
            // p：必须非常接近 1（0.95~1.05）。
            // 若 p >> 1，DoG 在平坦区域会整体为负，导致 tanh 全部饱和。
            p = (0.95 + l * 0.1).coerceIn(0.95, 1.05),
            // φ：tanh 锐度，大 = 阈值过渡更锐利
            phi = (5.0 + l * 25.0).coerceIn(5.0, 30.0),
            // ε：阈值偏移，必须与 DoG 值域匹配。
            // 灰度图 [0,1] 的 DoG 值域约 ±0.1，ε 必须在同一量级。
            epsilon = (0.08 - l * 0.07).coerceIn(0.01, 0.08),
            morphCloseSize = if (l < 0.3) 3 else if (l < 0.7) 2 else 1,
            morphOpenSize = 1,
            minComponentArea = (60 - l * 50).toInt().coerceIn(10, 60)
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

        val srcW = inputBitmap.width
        val srcH = inputBitmap.height
        val scaleRatio = computeScaleRatio(srcW, srcH, WORKING_MAX_DIM)
        val workW = (srcW * scaleRatio).toInt().coerceAtLeast(1)
        val workH = (srcH * scaleRatio).toInt().coerceAtLeast(1)

        // ── 防御：确保 Bitmap 可被 OpenCV lockPixels ──
        val safeInput = ensureSoftwareBitmap(inputBitmap)

        // ── OpenCV 处理在 Mat 上进行 ──
        var src = Mat()
        var resized = Mat()
        var gray = Mat()
        var gray64f = Mat()
        var gauss1 = Mat()
        var gauss2 = Mat()
        var dog = Mat()
        var diff = Mat()
        var scaled = Mat()
        var exp2x = Mat()
        var tanhResult = Mat()
        var edgeMask = Mat()
        var binary = Mat()
        var closed = Mat()
        var opened = Mat()
        var resultMat = Mat()
        var upscaled = Mat()

        try {
            // Step 1: Bitmap -> Mat
            Utils.bitmapToMat(safeInput, src)
            if (safeInput !== inputBitmap) safeInput.recycle()
            onProgress?.invoke(0.1f)

            // Step 2: 降采样（INTER_AREA 抗混叠）
            if (scaleRatio < 1.0) {
                Imgproc.resize(src, resized, Size(workW.toDouble(), workH.toDouble()),
                    0.0, 0.0, Imgproc.INTER_AREA)
            } else {
                src.copyTo(resized)
            }
            onProgress?.invoke(0.15f)

            // Step 3: 灰度转换（Android Bitmap 是 RGB 顺序）
            Imgproc.cvtColor(resized, gray, Imgproc.COLOR_RGB2GRAY)
            onProgress?.invoke(0.2f)

            // Step 4: 归一化到 [0,1]，转为 CV_64F 用于高精度 DoG 计算
            gray.convertTo(gray64f, CvType.CV_64F, 1.0 / 255.0)
            onProgress?.invoke(0.25f)

            // Step 5: GaussianBlur(σ)
            Imgproc.GaussianBlur(gray64f, gauss1, Size(0.0, 0.0), params.sigma)
            onProgress?.invoke(0.35f)

            // Step 6: GaussianBlur(k·σ)
            Imgproc.GaussianBlur(gray64f, gauss2, Size(0.0, 0.0), params.sigma * params.k)
            onProgress?.invoke(0.4f)

            // Step 7: DoG = gauss1 - p * gauss2
            Core.multiply(gauss2, Scalar(params.p), gauss2)
            Core.subtract(gauss1, gauss2, dog)
            onProgress?.invoke(0.5f)

            // Step 8: tanh 阈值化
            // T(D) = 1 + tanh(φ * (D - ε))
            // 利用公式：1 + tanh(x) = 2 * e^(2x) / (e^(2x) + 1)

            // diff = D - ε
            Core.subtract(dog, Scalar(params.epsilon), diff)
            // scaled = φ * diff
            Core.multiply(diff, Scalar(params.phi), scaled)

            // clip 到 [-50, 50] 防止 exp overflow
            Core.min(scaled, Scalar(50.0), scaled)
            Core.max(scaled, Scalar(-50.0), scaled)

            // doubleScaled = 2 * scaled
            val doubleScaled = Mat()
            Core.multiply(scaled, Scalar(2.0), doubleScaled)
            // exp2x = e^(2 * scaled)
            Core.exp(doubleScaled, exp2x)
            doubleScaled.release()

            // numerator = 2 * exp2x
            val numerator = Mat()
            Core.multiply(exp2x, Scalar(2.0), numerator)
            // denominator = exp2x + 1
            val denominator = Mat()
            Core.add(exp2x, Scalar(1.0), denominator)
            // tanhResult = numerator / denominator
            Core.divide(numerator, denominator, tanhResult)
            numerator.release()
            denominator.release()
            onProgress?.invoke(0.6f)

            // edgeMask = tanhResult <= 0.5（边缘 = 黑 = 0，背景 = 白 = 255）
            Imgproc.threshold(tanhResult, edgeMask, 0.5, 255.0, Imgproc.THRESH_BINARY_INV)
            onProgress?.invoke(0.65f)

            // Step 9: 转为 CV_8U（0=黑边缘，255=白背景）
            edgeMask.convertTo(binary, CvType.CV_8U)
            onProgress?.invoke(0.7f)

            // Step 10: 形态学闭运算（修复断线）
            if (params.morphCloseSize > 0) {
                val closeKernel = Imgproc.getStructuringElement(
                    Imgproc.MORPH_ELLIPSE,
                    Size(params.morphCloseSize.toDouble(), params.morphCloseSize.toDouble())
                )
                Imgproc.morphologyEx(binary, closed, Imgproc.MORPH_CLOSE, closeKernel)
                closeKernel.release()
            } else {
                binary.copyTo(closed)
            }
            onProgress?.invoke(0.75f)

            // Step 11: 形态学开运算（去噪）
            if (params.morphOpenSize > 0) {
                val openKernel = Imgproc.getStructuringElement(
                    Imgproc.MORPH_ELLIPSE,
                    Size(params.morphOpenSize.toDouble(), params.morphOpenSize.toDouble())
                )
                Imgproc.morphologyEx(closed, opened, Imgproc.MORPH_OPEN, openKernel)
                openKernel.release()
            } else {
                closed.copyTo(opened)
            }
            onProgress?.invoke(0.8f)

            // Step 12: 去除小连通域
            removeSmallComponents(opened, resultMat, params.minComponentArea)
            onProgress?.invoke(0.85f)

            // Step 13: 确保纯黑白
            Imgproc.threshold(resultMat, resultMat, 127.0, 255.0, Imgproc.THRESH_BINARY)
            onProgress?.invoke(0.9f)

            // Step 14: 反色 → 白底黑线
            Core.bitwise_not(resultMat, resultMat)
            onProgress?.invoke(0.92f)

            // Step 15: 升采样回原分辨率
            if (scaleRatio < 1.0) {
                Imgproc.resize(resultMat, upscaled, Size(srcW.toDouble(), srcH.toDouble()),
                    0.0, 0.0, Imgproc.INTER_CUBIC)
            } else {
                resultMat.copyTo(upscaled)
            }
            onProgress?.invoke(0.95f)

            // Step 16: Mat -> Bitmap
            val finalBitmap = Bitmap.createBitmap(srcW, srcH, Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(upscaled, finalBitmap)

            onProgress?.invoke(1.0f)
            return finalBitmap

        } finally {
            // 确保所有 Mat 被释放
            src.release()
            resized.release()
            gray.release()
            gray64f.release()
            gauss1.release()
            gauss2.release()
            dog.release()
            diff.release()
            scaled.release()
            exp2x.release()
            tanhResult.release()
            edgeMask.release()
            binary.release()
            closed.release()
            opened.release()
            resultMat.release()
            upscaled.release()
        }
    }

    /**
     * 计算降采样比例。
     */
    private fun computeScaleRatio(width: Int, height: Int, maxDim: Int): Double {
        return if (width <= maxDim && height <= maxDim) {
            1.0
        } else {
            maxDim.toDouble() / max(width, height)
        }
    }

    /**
     * 确保 Bitmap 不是 HARDWARE config 且为 ARGB_8888。
     */
    private fun ensureSoftwareBitmap(bitmap: Bitmap): Bitmap {
        if (bitmap.config == Bitmap.Config.ARGB_8888) {
            return bitmap
        }
        return if (bitmap.config == Bitmap.Config.HARDWARE) {
            val safe = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(safe)
            canvas.drawBitmap(bitmap, 0f, 0f, null)
            safe
        } else {
            bitmap.copy(Bitmap.Config.ARGB_8888, true) ?: bitmap
        }
    }

    /**
     * 去除面积小于 minArea 的连通域。
     */
    private fun removeSmallComponents(src: Mat, dst: Mat, minArea: Int) {
        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(
            src, contours, hierarchy,
            Imgproc.RETR_TREE,
            Imgproc.CHAIN_APPROX_SIMPLE
        )

        dst.create(src.size(), src.type())
        Core.bitwise_xor(dst, dst, dst)

        for (contour in contours) {
            val area = Imgproc.contourArea(contour)
            if (area >= minArea) {
                Imgproc.drawContours(dst, listOf(contour), -1, Scalar(255.0), -1)
            }
            contour.release()
        }
        hierarchy.release()
    }
}
