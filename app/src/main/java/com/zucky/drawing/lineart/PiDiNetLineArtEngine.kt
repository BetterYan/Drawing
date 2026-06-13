package com.zucky.drawing.lineart

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * 基于 PiDiNet-Tiny + LiteRT (TensorFlow Lite) 的线稿提取引擎。
 *
 * 模型输入：NHWC, [batch, 512, 512, 3], float32
 * 预处理：Resize 512x512 → /255.0 → ImageNet 归一化 (mean, std)
 * 模型输出：[batch, 512, 512, 1], float32（边缘概率图）
 * 后处理：Sigmoid → Threshold → Resize 回原尺寸 → 白底黑线
 */
class PiDiNetLineArtEngine(context: Context) : LineArtEngine {

    override val name: String = "PiDiNet"

    companion object {
        private const val MODEL_PATH = "pidinet_tiny.tflite"
        private const val MODEL_INPUT_SIZE = 512

        /** ImageNet 归一化参数 */
        private val IMAGENET_MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        private val IMAGENET_STD = floatArrayOf(0.229f, 0.224f, 0.225f)
    }

    private val interpreter: Interpreter

    init {
        val modelBuffer = loadModelFile(context, MODEL_PATH)
        val options = Interpreter.Options().apply {
            // 设置线程数，利用多核 CPU
            setNumThreads(4)
            // 使用 GPU Delegate（如果设备支持）
            // 注意：PiDiNet 是轻量模型，CPU 推理已足够快
            // 如需 GPU 加速，取消下面注释并添加依赖 org.tensorflow:tensorflow-lite-gpu
            // try {
            //     val gpuDelegate = org.tensorflow.lite.gpu.GpuDelegate()
            //     addDelegate(gpuDelegate)
            // } catch (e: Exception) {
            //     // GPU 不可用，回退到 CPU
            // }
        }
        interpreter = Interpreter(modelBuffer, options)
    }

    override suspend fun convert(
        inputBitmap: Bitmap,
        detailLevel: Float,
        onProgress: ((Float) -> Unit)?
    ): Bitmap {
        require(!inputBitmap.isRecycled) { "Input bitmap is recycled" }
        require(inputBitmap.width > 0 && inputBitmap.height > 0) { "Input bitmap has zero dimensions" }

        onProgress?.invoke(0.05f)

        val srcW = inputBitmap.width
        val srcH = inputBitmap.height

        // ── 防御：确保 Bitmap 可被处理 ──
        val safeInput = ensureSoftwareBitmap(inputBitmap)

        // ── Step 1: 预处理：Bitmap → float[NHWC] ──
        val inputBuffer = preprocessBitmap(safeInput, MODEL_INPUT_SIZE)
        if (safeInput !== inputBitmap) safeInput.recycle()
        onProgress?.invoke(0.3f)

        // ── Step 2: LiteRT 推理 ──
        // 输出形状: [1, 512, 512, 1]
        val outputShape = interpreter.getOutputTensor(0).shape()
        val outputBuffer = Array(outputShape[0]) {
            Array(outputShape[1]) {
                Array(outputShape[2]) {
                    FloatArray(outputShape[3])
                }
            }
        }
        interpreter.run(inputBuffer, outputBuffer)
        onProgress?.invoke(0.7f)

        // ── Step 3: 后处理：输出 → Bitmap → 二值线稿 ──
        val lineArtBitmap = postprocessOutput(
            outputBuffer, srcW, srcH, detailLevel
        )
        onProgress?.invoke(0.95f)

        onProgress?.invoke(1.0f)
        return lineArtBitmap
    }

    /**
     * 预处理：将 Bitmap 转为 TFLite 输入 float[NHWC]。
     */
    private fun preprocessBitmap(bitmap: Bitmap, targetSize: Int): Array<Array<Array<FloatArray>>> {
        // Resize 到目标尺寸
        val scaled = Bitmap.createScaledBitmap(bitmap, targetSize, targetSize, true)

        // NHWC 格式填充
        val input = Array(1) {
            Array(targetSize) { y ->
                Array(targetSize) { x ->
                    FloatArray(3)
                }
            }
        }

        val pixels = IntArray(targetSize * targetSize)
        scaled.getPixels(pixels, 0, targetSize, 0, 0, targetSize, targetSize)
        if (scaled !== bitmap) scaled.recycle()

        for (y in 0 until targetSize) {
            for (x in 0 until targetSize) {
                val px = pixels[y * targetSize + x]
                // ARGB_8888 中提取 RGB，范围 [0, 255]
                val r = (px shr 16) and 0xFF
                val g = (px shr 8) and 0xFF
                val b = px and 0xFF

                // /255.0 → ImageNet 归一化
                input[0][y][x][0] = (r / 255.0f - IMAGENET_MEAN[0]) / IMAGENET_STD[0]
                input[0][y][x][1] = (g / 255.0f - IMAGENET_MEAN[1]) / IMAGENET_STD[1]
                input[0][y][x][2] = (b / 255.0f - IMAGENET_MEAN[2]) / IMAGENET_STD[2]
            }
        }

        return input
    }

    /**
     * 后处理：TFLite 输出 → 白底黑线线稿 Bitmap。
     *
     * @param detailLevel 映射到二值化阈值：
     *   0 = 高阈值(少细节)，1 = 低阈值(多细节)
     */
    private fun postprocessOutput(
        output: Array<Array<Array<FloatArray>>>,
        targetW: Int,
        targetH: Int,
        detailLevel: Float
    ): Bitmap {
        val h = output[0].size
        val w = output[0][0].size

        // detailLevel → 阈值映射（0=简略高阈值, 1=精细低阈值）
        val threshold = (0.7f - detailLevel * 0.5f).coerceIn(0.15f, 0.7f)

        // 创建灰度 Bitmap
        val grayBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)

        for (y in 0 until h) {
            for (x in 0 until w) {
                // PiDiNet 输出为边缘概率，应用 sigmoid（如果模型未内置）
                // 实际上 PiDiNet 原始实现通常会在最后加 sigmoid，但不同导出方式可能不同
                // 这里做安全处理：如果值已经在 [0,1] 范围内则直接使用，否则 sigmoid
                var prob = output[0][y][x][0]
                if (prob < 0f || prob > 1f) {
                    prob = sigmoid(prob)
                }

                // 二值化：概率 > threshold → 黑线(0)，否则 → 白背景(255)
                val gray = if (prob > threshold) 0 else 255
                grayBitmap.setPixel(x, y, Color.rgb(gray, gray, gray))
            }
        }

        // 如果目标尺寸与模型输出不同，使用 OpenCV resize（质量更好）
        return if (w != targetW || h != targetH) {
            resizeBitmap(grayBitmap, targetW, targetH)
        } else {
            grayBitmap
        }
    }

    /**
     * 使用 OpenCV 高质量 resize Bitmap。
     */
    private fun resizeBitmap(bitmap: Bitmap, targetW: Int, targetH: Int): Bitmap {
        var src = Mat()
        var dst = Mat()
        try {
            Utils.bitmapToMat(bitmap, src)
            Imgproc.resize(src, dst, Size(targetW.toDouble(), targetH.toDouble()),
                0.0, 0.0, Imgproc.INTER_CUBIC)
            val result = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(dst, result)
            return result
        } finally {
            src.release()
            dst.release()
            bitmap.recycle()
        }
    }

    /**
     * Sigmoid 函数。
     */
    private fun sigmoid(x: Float): Float {
        return 1.0f / (1.0f + kotlin.math.exp(-x.coerceIn(-20f, 20f)))
    }

    /**
     * 从 assets 加载 TFLite 模型文件为 MappedByteBuffer。
     */
    private fun loadModelFile(context: Context, modelPath: String): MappedByteBuffer {
        val fileDescriptor = context.assets.openFd(modelPath)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
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

    override fun isAvailable(): Boolean {
        return true
    }

    /**
     * 释放 Interpreter 资源。
     */
    fun close() {
        interpreter.close()
    }
}
