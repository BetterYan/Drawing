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
 * 基于 OpenCV 的线稿提取引擎（参数优化版）。
 *
 * 优化后的管线：
 * 1. Bitmap → Mat（ARGB_8888 保证）
 * 2. OpenCV resize 降采样（INTER_AREA 抗混叠）
 * 3. 灰度转换（COLOR_RGB2GRAY，修复通道顺序）
 * 4. CLAHE 对比度增强
 * 5. 双边滤波（保边去噪，参数收紧）
 * 6. 自适应阈值（主体轮廓）+ Canny（精细边缘）
 * 7. 加权融合（addWeighted 0.6+0.4）+ 二值化
 * 8. 形态学闭运算（修复断线）
 * 9. 形态学开运算（去噪）
 * 10. 去除小连通域（RETR_TREE 保留内部细节）
 * 11. 反色 → 白底黑线
 * 12. OpenCV resize 升采样（INTER_CUBIC）→ Bitmap
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
            // 双边滤波（sigma 收紧，避免过度平滑）
            bilateralDiameter = (15 - l * 6).toInt().coerceIn(5, 15),
            bilateralSigmaColor = (60 - l * 30).toDouble().coerceIn(30.0, 60.0),
            bilateralSigmaSpace = (60 - l * 30).toDouble().coerceIn(30.0, 60.0),

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

        val srcW = inputBitmap.width
        val srcH = inputBitmap.height
        val scaleRatio = computeScaleRatio(srcW, srcH, WORKING_MAX_DIM)
        val workW = (srcW * scaleRatio).toInt().coerceAtLeast(1)
        val workH = (srcH * scaleRatio).toInt().coerceAtLeast(1)

        // ── 防御：确保 Bitmap 可被 OpenCV lockPixels（非 HARDWARE / ARGB_8888） ──
        val safeInput = ensureSoftwareBitmap(inputBitmap)

        // ── OpenCV 处理在 Mat 上进行 ──
        var src = Mat()
        var resized = Mat()
        var gray = Mat()
        var enhanced = Mat()
        var filtered = Mat()
        var adaptive = Mat()
        var canny = Mat()
        var combined = Mat()
        var binarized = Mat()
        var closed = Mat()
        var opened = Mat()
        var resultMat = Mat()
        var upscaled = Mat()

        try {
            // Step 1: Bitmap -> Mat
            Utils.bitmapToMat(safeInput, src)
            if (safeInput !== inputBitmap) safeInput.recycle()
            onProgress?.invoke(0.1f)

            // Step 2: 降采样（INTER_AREA 抗混叠，优于 Android 的双线性插值）
            if (scaleRatio < 1.0) {
                Imgproc.resize(src, resized, Size(workW.toDouble(), workH.toDouble()),
                    0.0, 0.0, Imgproc.INTER_AREA)
            } else {
                src.copyTo(resized)
            }
            onProgress?.invoke(0.15f)

            // Step 3: 灰度转换（Android Bitmap 是 RGB 顺序，不是 BGR）
            Imgproc.cvtColor(resized, gray, Imgproc.COLOR_RGB2GRAY)
            onProgress?.invoke(0.25f)

            // Step 4: CLAHE 对比度增强（自适应直方图均衡化）
            val clahe = Imgproc.createCLAHE(2.0, Size(8.0, 8.0))
            clahe.apply(gray, enhanced)
            clahe.collectGarbage()
            onProgress?.invoke(0.3f)

            // Step 5: 双边滤波（保边去噪）
            Imgproc.bilateralFilter(
                enhanced, filtered,
                params.bilateralDiameter,
                params.bilateralSigmaColor,
                params.bilateralSigmaSpace
            )
            onProgress?.invoke(0.4f)

            // Step 6: 自适应阈值（提取主体轮廓）
            Imgproc.adaptiveThreshold(
                filtered, adaptive,
                255.0,
                Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
                Imgproc.THRESH_BINARY_INV,
                params.adaptiveBlockSize,
                params.adaptiveC
            )
            onProgress?.invoke(0.5f)

            // Step 7: Canny 边缘检测（提取精细边缘）
            Imgproc.Canny(
                filtered, canny,
                params.cannyThreshold1,
                params.cannyThreshold2
            )
            onProgress?.invoke(0.55f)

            // Step 8: 加权融合（替代 bitwise_or，减少噪声叠加）
            Core.addWeighted(adaptive, 0.6, canny, 0.4, 0.0, combined)
            onProgress?.invoke(0.6f)

            // Step 9: 融合后整体二值化（确保纯黑白）
            Imgproc.threshold(combined, binarized, 127.0, 255.0, Imgproc.THRESH_BINARY)
            onProgress?.invoke(0.65f)

            // Step 10: 形态学闭运算（修复断线）
            if (params.morphCloseSize > 0) {
                val closeKernel = Imgproc.getStructuringElement(
                    Imgproc.MORPH_ELLIPSE,
                    Size(params.morphCloseSize.toDouble(), params.morphCloseSize.toDouble())
                )
                Imgproc.morphologyEx(binarized, closed, Imgproc.MORPH_CLOSE, closeKernel)
                closeKernel.release()
            } else {
                binarized.copyTo(closed)
            }
            onProgress?.invoke(0.7f)

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
            onProgress?.invoke(0.75f)

            // Step 12: 去除小连通域（RETR_TREE 保留内部孔洞细节）
            removeSmallComponents(opened, resultMat, params.minComponentArea)
            onProgress?.invoke(0.8f)

            // Step 13: 反色前再次确保纯黑白
            Imgproc.threshold(resultMat, resultMat, 127.0, 255.0, Imgproc.THRESH_BINARY)
            onProgress?.invoke(0.85f)

            // Step 14: 反色 → 白底黑线
            Core.bitwise_not(resultMat, resultMat)
            onProgress?.invoke(0.9f)

            // Step 15: 升采样回原分辨率（INTER_CUBIC 质量优于双线性）
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
            // 确保所有 Mat 被释放，防止 native 内存泄漏
            src.release()
            resized.release()
            gray.release()
            enhanced.release()
            filtered.release()
            adaptive.release()
            canny.release()
            combined.release()
            binarized.release()
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
     * 去除面积小于 minArea 的连通域（基于轮廓查找）。
     * 使用 RETR_TREE 保留内部孔洞层次结构（如眼睛、嘴等内部细节）。
     */
    private fun removeSmallComponents(src: Mat, dst: Mat, minArea: Int) {
        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(
            src, contours, hierarchy,
            Imgproc.RETR_TREE,
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
