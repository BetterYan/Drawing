package com.zucky.drawing.lineart

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.max

/**
 * 线稿后处理：形态学运算、轮廓过滤、反色、升采样等。
 * 将边缘检测结果转换为干净的白底黑线线稿。
 */
object LineArtPostprocessor {

    /**
     * 形态学闭运算（先膨胀后腐蚀）。
     * 用于修复线稿中的断口。
     * @param binary 二值化数组（true=前景/边缘，false=背景）
     * @param w 宽度
     * @param h 高度
     * @param kernelRadius 核半径（1=修复1px断口）
     * @return 修复后的二值化数组
     */
    fun morphologicalClose(
        binary: BooleanArray, w: Int, h: Int, kernelRadius: Int
    ): BooleanArray {
        if (kernelRadius <= 0) return binary

        val total = w * h
        // Dilate
        val dilated = BooleanArray(total)
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (!binary[y * w + x]) continue
                for (dy in -kernelRadius..kernelRadius) {
                    val ny = y + dy
                    if (ny < 0 || ny >= h) continue
                    for (dx in -kernelRadius..kernelRadius) {
                        val nx = x + dx
                        if (nx < 0 || nx >= w) continue
                        dilated[ny * w + nx] = true
                    }
                }
            }
        }

        // Erode
        val eroded = BooleanArray(total)
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (!dilated[y * w + x]) continue
                var allTrue = true
                outer@ for (dy in -kernelRadius..kernelRadius) {
                    val ny = y + dy
                    if (ny < 0 || ny >= h) continue
                    for (dx in -kernelRadius..kernelRadius) {
                        val nx = x + dx
                        if (nx < 0 || nx >= w) continue
                        if (!dilated[ny * w + nx]) {
                            allTrue = false
                            break@outer
                        }
                    }
                }
                eroded[y * w + x] = allTrue
            }
        }
        return eroded
    }

    /**
     * 形态学开运算（先腐蚀后膨胀）。
     * 用于去除孤立噪声点。
     */
    fun morphologicalOpen(
        binary: BooleanArray, w: Int, h: Int, kernelRadius: Int
    ): BooleanArray {
        if (kernelRadius <= 0) return binary

        val total = w * h
        // Erode
        val eroded = BooleanArray(total)
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (!binary[y * w + x]) continue
                var allTrue = true
                outer@ for (dy in -kernelRadius..kernelRadius) {
                    val ny = y + dy
                    if (ny < 0 || ny >= h) continue
                    for (dx in -kernelRadius..kernelRadius) {
                        val nx = x + dx
                        if (nx < 0 || nx >= w) continue
                        if (!binary[ny * w + nx]) {
                            allTrue = false
                            break@outer
                        }
                    }
                }
                eroded[y * w + x] = allTrue
            }
        }

        // Dilate
        val dilated = BooleanArray(total)
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (!eroded[y * w + x]) continue
                for (dy in -kernelRadius..kernelRadius) {
                    val ny = y + dy
                    if (ny < 0 || ny >= h) continue
                    for (dx in -kernelRadius..kernelRadius) {
                        val nx = x + dx
                        if (nx < 0 || nx >= w) continue
                        dilated[ny * w + nx] = true
                    }
                }
            }
        }
        return dilated
    }

    /**
     * 去除小于 minArea 像素的连通域（噪声碎片）。
     * 使用简单的 4方向 BFS 标记连通域。
     */
    fun removeSmallComponents(
        binary: BooleanArray, w: Int, h: Int, minArea: Int
    ): BooleanArray {
        val total = w * h
        val visited = BooleanArray(total)
        val result = BooleanArray(total)

        // 预分配 BFS 队列和连通域缓存（避免每次循环分配大数组）
        val queue = IntArray(total)
        val component = IntArray(total)

        for (startIdx in 0 until total) {
            if (!binary[startIdx] || visited[startIdx]) continue

            // BFS 找连通域
            var head = 0
            var tail = 0
            var compSize = 0
            queue[tail++] = startIdx
            visited[startIdx] = true

            while (head < tail) {
                val idx = queue[head++]
                component[compSize++] = idx
                val x = idx % w
                val y = idx / w

                // 4邻域
                if (x > 0) {
                    val nIdx = idx - 1
                    if (binary[nIdx] && !visited[nIdx]) {
                        visited[nIdx] = true
                        queue[tail++] = nIdx
                    }
                }
                if (x < w - 1) {
                    val nIdx = idx + 1
                    if (binary[nIdx] && !visited[nIdx]) {
                        visited[nIdx] = true
                        queue[tail++] = nIdx
                    }
                }
                if (y > 0) {
                    val nIdx = idx - w
                    if (binary[nIdx] && !visited[nIdx]) {
                        visited[nIdx] = true
                        queue[tail++] = nIdx
                    }
                }
                if (y < h - 1) {
                    val nIdx = idx + w
                    if (binary[nIdx] && !visited[nIdx]) {
                        visited[nIdx] = true
                        queue[tail++] = nIdx
                    }
                }
            }

            // 只保留足够大的连通域
            if (compSize >= minArea) {
                for (i in 0 until compSize) {
                    result[component[i]] = true
                }
            }
        }
        return result
    }

    /**
     * 将边缘检测结果转换为白底黑线线稿 Bitmap。
     * @param edgeMask true=边缘线，false=背景
     * @param w 宽度
     * @param h 高度
     * @return 白底黑线 Bitmap
     */
    fun toWhiteBlackBitmap(edgeMask: BooleanArray, w: Int, h: Int): Bitmap {
        val pixels = IntArray(w * h)
        for (i in edgeMask.indices) {
            pixels[i] = if (edgeMask[i]) {
                Color.BLACK // 边缘 = 黑色
            } else {
                Color.WHITE // 背景 = 白色
            }
        }
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        return bitmap
    }

    /**
     * 将灰度边缘响应图转换为白底黑线线稿。
     * 灰度值越高 = 边缘越强 → 转为黑色线条。
     * @param edgeResponse 边缘响应灰度数组 [0..255]
     * @param w 宽度
     * @param h 高度
     * @param threshold 二值化阈值
     * @return 白底黑线 Bitmap
     */
    fun edgeResponseToLineArt(
        edgeResponse: IntArray, w: Int, h: Int, threshold: Int = 128
    ): Bitmap {
        val pixels = IntArray(w * h)
        for (i in edgeResponse.indices) {
            // 高响应 = 边缘 → 黑色; 低响应 = 背景 → 白色
            pixels[i] = if (edgeResponse[i] > threshold) Color.BLACK else Color.WHITE
        }
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        return bitmap
    }

    /**
     * 升采样到目标分辨率。
     */
    fun upscale(input: Bitmap, targetW: Int, targetH: Int): Bitmap {
        if (input.width == targetW && input.height == targetH) return input
        return Bitmap.createScaledBitmap(input, targetW, targetH, true)
    }
}
