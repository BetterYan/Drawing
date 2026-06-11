package com.zucky.drawing.lineart

import android.content.Context
import android.graphics.Bitmap

/**
 * ML 线稿引擎（基于 ONNX Runtime）。
 *
 * 当前为骨架实现。完整使用需要：
 * 1. Python 环境中将 ControlNet lineart preprocessor 转换为 ONNX 格式
 * 2. 将 .onnx 模型文件放入 assets/ 目录
 * 3. 添加 ONNX Runtime Android 依赖
 *
 * 模型转换参考：
 *   pip install controlnet_aux torch onnx onnxruntime
 *   from controlnet_aux import LineartDetector
 *   detector = LineartDetector.from_pretrained("lllyasviel/Annotators")
 *   # ... export to ONNX via torch.onnx.export()
 */
class MlLineArtEngine(private val context: Context) : LineArtEngine {

    companion object {
        private const val MODEL_ASSET_NAME = "lineart.onnx"
        private const val INPUT_SIZE = 512
    }

    override val name: String = "ML (ControlNet)"

    // ONNX Runtime 相关（需要添加依赖后取消注释）
    // private var env: OrtEnvironment? = null
    // private var session: OrtSession? = null

    override fun isAvailable(): Boolean {
        // 检查模型文件是否存在于 assets
        return try {
            context.assets.open(MODEL_ASSET_NAME).use { it.available() > 0 }
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun convert(
        inputBitmap: Bitmap,
        detailLevel: Float,
        onProgress: ((Float) -> Unit)?
    ): Bitmap {
        if (!isAvailable()) {
            throw IllegalStateException("ML 模型文件 ($MODEL_ASSET_NAME) 不存在")
        }

        // TODO: 添加 ONNX Runtime 依赖后实现完整推理
        // 当前回退到 XDoG 引擎
        //
        // 完整实现需要：
        // 1. 预处理: resize 512x512, normalize [0,1], 转 NCHW float tensor
        // 2. OrtSession.run() 推理
        // 3. 后处理: sigmoid → 二值化 → 形态学清理
        return XDoGLineArtEngine().convert(inputBitmap, detailLevel, onProgress)
    }

    /**
     * 将 Bitmap 转换为 NCHW 格式的 float 数组（供 ONNX 推理使用）。
     * RGB 通道分离，归一化到 [0, 1]。
     */
    @Suppress("unused")
    private fun bitmapToFloatTensor(bitmap: Bitmap, size: Int): FloatArray {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        // 如果尺寸不匹配，需要 resize
        if (w != size || h != size) {
            val resized = Bitmap.createScaledBitmap(bitmap, size, size, true)
            resized.getPixels(pixels, 0, size, 0, 0, size, size)
            resized.recycle()
        }

        val actualW = if (w != size) size else w
        val actualH = if (h != size) size else h
        val total = actualW * actualH

        // NCHW: [batch=1, channels=3, height, width]
        val tensor = FloatArray(3 * total)
        for (i in 0 until total) {
            val pixel = pixels[i]
            tensor[i] = ((pixel shr 16) and 0xFF) / 255f          // R
            tensor[total + i] = ((pixel shr 8) and 0xFF) / 255f   // G
            tensor[2 * total + i] = (pixel and 0xFF) / 255f       // B
        }
        return tensor
    }
}
