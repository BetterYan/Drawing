package com.zucky.drawing.lineart

import android.graphics.Bitmap
import android.graphics.Canvas
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.max

/**
 * 基于 OpenCV 的线稿提取引擎。
 *
 * 管线：
 * 1. 降采样到工作分辨率（768px）
 * 2. 灰度转换
 * 3. 双边滤波（保边去噪）
 * 4. 自适应阈值（提取主体轮廓）
 * 5. Canny 边缘检测（提取精细边缘）
 * 6. 合并两者
 * 7. 形态学闭运算（修复断线）
 * 8. 形态学开运算（去噪）
 * 9. 反色 → 白底黑线
 * 10. 升采样回原始分辨率
 */
class OpenCVLineArtEngine : LineArtEngine {

    override val name: String = "OpenCV"

    companion object {
        private const val WORKING_MAX_DIM = 768

        /** 必须先调用 OpenCVLoader.initLocal() */
        fun isOpenCvInitialized(): Boolean = OpenCVLoader.initLocal()
    }

    /**
     * 根据 detailLevel (0~1) 计算参数。
     * 0 = 粗线条/少细节，1 = 细线条/多细节。
     */
    private fun computeParams(level: Float): OpenCVParams {
        val l = level.coerceIn(0f, 1f)
        return OpenCVParams(
            // 双边滤波
            bilateralDiameter = (15 - l * 6).toInt().coerceIn(5, 15),
            bilateralSigmaColor = (80 - l * 40).toDouble().coerceIn(20.0, 80.0),
            bilateralSigmaSpace = (80 - l * 40).toDouble().coerceIn(20.0, 80.0),

            // 自适应阈值
            adaptiveBlockSize = (41 - l * 20).toInt().coerceIn(11, 41).let {
                if (it % 2 == 0) it + 1 else it // 必须奇数
            },
            adaptiveC = (10 - l * 8).toDouble().coerceIn(2.0, 10.0),

            // Canny
            cannyThreshold1 = (80 - l * 50).toDouble().coerceIn(20.0, 80.0),
            cannyThreshold2 = (160 - l * 80).toDouble().coerceIn(40.0, 160.0),

            // 形态学
            morphCloseSize = if (l < 0.3) 3 else if (l < 0.7) 2 else 1,
            morphOpenSize = 1,

            // 小连通域过滤（以工作分辨率 768 为基准）
            minComponentArea = (80 - l * 60).toInt().coerceIn(10, 80)
        )
    }

    private data class OpenCVParams(
        val bilateralDiameter: Int,
        val bilateralSigmaColor: Double,
        val bilateralSigmaSpace: Double,
        val adaptiveBlockSize: Int,
        val adaptiveC: Double,
        val cannyThreshold1: Double,
        val cannyThreshold2: Double,
        val morphCloseSize: Int,
        val morphOpenSize: Int,
        val minComponentArea: Int
    )

    override suspend fun convert(
        inputBitmap: Bitmap,
        detailLevel: Float,
        onProgress: ((Float) -> Unit)?
    ): Bitmap {
        require(!inputBitmap.isRecycled) { "Input bitmap is recycled" }
        require(inputBitmap.width > 0 && inputBitmap.height > 0) { "Input bitmap has zero dimensions" }

        val params = computeParams(detailLevel)
        onProgress?.invoke(0.05f)

        // ── 防御：确保 Bitmap 可被 OpenCV lockPixels（非 HARDWARE / ARGB_8888） ──
        // Android 10+ ImageDecoder 可能默认创建 HARDWARE Bitmap，OpenCV 无法 lockPixels
        val safeInput = ensureSoftwareBitmap(inputBitmap)

        // ── Step 1: 降采样 ──
        val (workingBitmap, scaleRatio) = downsampleBitmap(safeInput, WORKING_MAX_DIM)
        onProgress?.invoke(0.15f)

        // 如果 safeInput 是复制的中间产物，且不是 workingBitmap 本身，则回收
        if (safeInput !== inputBitmap && safeInput !== workingBitmap) {
            safeInput.recycle()
        }

        val w = workingBitmap.width
        val h = workingBitmap.height

        // ── OpenCV 处理在 Mat 上进行 ──
        var src = Mat()
        var gray = Mat()
        var filtered = Mat()
        var adaptive = Mat()
        var canny = Mat()
        var combined = Mat()
        var closed = Mat()
        var opened = Mat()
        var resultMat = Mat()

        try {
            // Step 2: Bitmap -> Mat
            Utils.bitmapToMat(workingBitmap, src)
            // 释放降采样产生的中间 Bitmap（注意：如果 downsampleBitmap 返回的是 safeInput 本身，
            // 则上面的 alreadyRecycled 逻辑已处理；这里只回收明确新创建的 workingBitmap）
            if (workingBitmap !== safeInput) workingBitmap.recycle()
            onProgress?.invoke(0.2f)

            // Step 3: 灰度转换
            Imgproc.cvtColor(src, gray, Imgproc.COLOR_BGR2GRAY)
            onProgress?.invoke(0.3f)

            // Step 4: 双边滤波（保边去噪）
            Imgproc.bilateralFilter(
                gray, filtered,
                params.bilateralDiameter,
                params.bilateralSigmaColor,
                params.bilateralSigmaSpace
            )
            onProgress?.invoke(0.4f)

            // Step 5: 自适应阈值（提取主体轮廓）
            Imgproc.adaptiveThreshold(
                filtered, adaptive,
                255.0,
                Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
                Imgproc.THRESH_BINARY_INV,
                params.adaptiveBlockSize,
                params.adaptiveC
            )
            onProgress?.invoke(0.55f)

            // Step 6: Canny 边缘检测（提取精细边缘）
            Imgproc.Canny(
                filtered, canny,
                params.cannyThreshold1,
                params.cannyThreshold2
            )
            onProgress?.invoke(0.65f)

            // Step 7: 合并两者（取并集）
            Core.bitwise_or(adaptive, canny, combined)
            onProgress?.invoke(0.7f)

            // Step 8: 形态学闭运算（修复断线）
            if (params.morphCloseSize > 0) {
                val closeKernel = Imgproc.getStructuringElement(
                    Imgproc.MORPH_ELLIPSE,
                    Size(params.morphCloseSize.toDouble(), params.morphCloseSize.toDouble())
                )
                Imgproc.morphologyEx(combined, closed, Imgproc.MORPH_CLOSE, closeKernel)
                closeKernel.release()
            } else {
                combined.copyTo(closed)
            }
            onProgress?.invoke(0.75f)

            // Step 9: 形态学开运算（去噪）
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

            // Step 10: 去除小连通域
            removeSmallComponents(opened, resultMat, params.minComponentArea)
            onProgress?.invoke(0.85f)

            // Step 11: 反色 → 白底黑线（当前是黑底白线）
            Core.bitwise_not(resultMat, resultMat)
            onProgress?.invoke(0.9f)

            // Step 12: Mat -> Bitmap
            val lineArt = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(resultMat, lineArt)

            // Step 13: 升采样回原始分辨率
            val finalBitmap = if (scaleRatio < 1f) {
                Bitmap.createScaledBitmap(lineArt, inputBitmap.width, inputBitmap.height, true)
                    .also { lineArt.recycle() }
            } else {
                lineArt
            }

            onProgress?.invoke(1.0f)
            return finalBitmap

        } finally {
            // 确保所有 Mat 被释放，防止 native 内存泄漏
            src.release()
            gray.release()
            filtered.release()
            adaptive.release()
            canny.release()
            combined.release()
            closed.release()
            opened.release()
            resultMat.release()
        }
    }

    /**
     * 确保 Bitmap 不是 HARDWARE config 且为 ARGB_8888，OpenCV 才能 lockPixels。
     * HARDWARE Bitmap 不能直接 copy()，必须通过 Canvas 绘制。
     */
    private fun ensureSoftwareBitmap(bitmap: Bitmap): Bitmap {
        // 已经是安全的软件 Bitmap
        if (bitmap.config == Bitmap.Config.ARGB_8888) {
            return bitmap
        }
        // HARDWARE Bitmap 无法直接 copy()，需要 Canvas 绘制
        return if (bitmap.config == Bitmap.Config.HARDWARE) {
            val safe = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(safe)
            canvas.drawBitmap(bitmap, 0f, 0f, null)
            safe
        } else {
            // 其他 config（如 RGB_565）复制为 ARGB_8888
            bitmap.copy(Bitmap.Config.ARGB_8888, true) ?: bitmap
        }
    }

    /**
     * 等比降采样，返回 (Bitmap, scaleRatio)。
     * 如果无需降采样，返回原 Bitmap 和 1.0f。
     */
    private fun downsampleBitmap(input: Bitmap, maxDim: Int): Pair<Bitmap, Float> {
        val w = input.width
        val h = input.height
        if (w <= maxDim && h <= maxDim) return input to 1f

        val scale = maxDim.toFloat() / max(w, h)
        val newW = (w * scale).toInt().coerceAtLeast(1)
        val newH = (h * scale).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(input, newW, newH, true)
        return scaled to scale
    }

    /**
     * 去除面积小于 minArea 的连通域（基于轮廓查找）。
     */
    private fun removeSmallComponents(src: Mat, dst: Mat, minArea: Int) {
        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(
            src, contours, hierarchy,
            Imgproc.RETR_EXTERNAL,
            Imgproc.CHAIN_APPROX_SIMPLE
        )

        // 创建空白画布
        dst.create(src.size(), src.type())
        Core.bitwise_xor(dst, dst, dst) // 清零

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
