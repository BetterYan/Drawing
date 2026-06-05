package com.zucky.drawing.drawing

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import java.util.*

/**
 * 自定义绘图画布 View
 * 支持：
 * - 模板底层显示
 * - 画笔自由绘制
 * - 区域约束涂色（不超出素描线框）
 * - 橡皮擦
 * - 填充（flood fill）
 * - 撤销/重做
 */
class DrawingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ── 模板 ──────────────────────────────────

    /** 模板底层线稿 */
    var templateBitmap: Bitmap? = null
        set(value) {
            field = value
            if (value != null) {
                if (drawingBitmap == null) {
                    initDrawingBitmap()
                }
                // 异步分析区域划分
                analyzeRegionsAsync(value)
            } else {
                regionMask = null
            }
            invalidate()
        }

    // ── 绘图层 ────────────────────────────────

    private var drawingBitmap: Bitmap? = null
    private var drawingCanvas: Canvas? = null

    // ── 画笔 ──────────────────────────────────

    private val paint = Paint().apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 20f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        isAntiAlias = true
    }

    private val eraserPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 40f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        isAntiAlias = true
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }

    private val fillPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    // ── 工具状态 ──────────────────────────────

    enum class Tool { BRUSH, ERASER, FILL }
    var currentTool: Tool = Tool.BRUSH

    var currentColor: Int = Color.RED
        set(value) {
            field = value
            paint.color = value
            fillPaint.color = value
        }

    var strokeWidth: Float = 20f
        set(value) {
            field = value
            paint.strokeWidth = value
        }

    // ── 区域约束涂色 ──────────────────────────

    /** 是否启用区域约束（默认开启） */
    var constrainToRegion: Boolean = true

    /** 区域分析是否完成 */
    val isRegionReady: Boolean get() = regionMask != null

    // 区域掩码：每个像素 -> 区域ID（0=边界线，>=1=可涂色区域）
    private var regionMask: IntArray? = null
    private var regionWidth: Int = 0
    private var regionHeight: Int = 0
    private var currentRegionId: Int = -1

    /** 区域分析进度监听 */
    var onRegionReadyListener: (() -> Unit)? = null

    // ── 撤销/重做 ─────────────────────────────

    private data class DrawAction(val bitmap: Bitmap)

    private val undoStack = Stack<DrawAction>()
    private val redoStack = Stack<DrawAction>()
    private val maxHistory = 30

    private var currentPath: Path? = null

    // ── 坐标变换 ──────────────────────────────

    private val viewMatrix = Matrix()
    private val invertMatrix = Matrix()

    private var lastTouchX = 0f
    private var lastTouchY = 0f

    // 用于区域约束的临时像素缓存（避免频繁分配）
    private var tempConstraintPixels: IntArray? = null

    // ==================================================================
    //  初始化
    // ==================================================================

    fun initDrawingBitmap() {
        val w = templateBitmap?.width ?: width
        val h = templateBitmap?.height ?: height
        if (w <= 0 || h <= 0) return

        drawingBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        drawingCanvas = Canvas(drawingBitmap!!)
        undoStack.clear()
        redoStack.clear()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (drawingBitmap == null) {
            initDrawingBitmap()
        }
        templateBitmap?.let { tmpl ->
            val scaleX = w.toFloat() / tmpl.width
            val scaleY = h.toFloat() / tmpl.height
            val scale = minOf(scaleX, scaleY)
            val dx = (w - tmpl.width * scale) / 2f
            val dy = (h - tmpl.height * scale) / 2f
            viewMatrix.reset()
            viewMatrix.postScale(scale, scale)
            viewMatrix.postTranslate(dx, dy)
            viewMatrix.invert(invertMatrix)
        }
    }

    // ==================================================================
    //  区域分析：预计算模板中每个像素属于哪个封闭区域
    // ==================================================================

    /**
     * 在后台线程分析模板的区域划分。
     * 模板中的深色像素 = 边界线（不可涂色），浅色/白色像素 = 可涂色区域。
     * 每个连通的可涂色区域被分配一个唯一 ID。
     */
    private fun analyzeRegionsAsync(template: Bitmap) {
        val w = template.width
        val h = template.height
        val totalPixels = w * h

        // 先读取模板像素（必须在主线程拿 Bitmap，分析放后台）
        val templatePixels = IntArray(totalPixels)
        template.getPixels(templatePixels, 0, w, 0, 0, w, h)

        val tmpRegionWidth = w
        val tmpRegionHeight = h

        Thread {
            val mask = analyzeRegionsSync(templatePixels, tmpRegionWidth, tmpRegionHeight)

            post {
                regionMask = mask
                regionWidth = tmpRegionWidth
                regionHeight = tmpRegionHeight
                currentRegionId = -1
                // 清除之前分配但未使用的临时缓存
                tempConstraintPixels = null
                onRegionReadyListener?.invoke()
            }
        }.start()
    }

    /**
     * 同步分析区域划分（运行在后台线程）。
     * @return IntArray 每个像素的 regionId
     */
    private fun analyzeRegionsSync(pixels: IntArray, w: Int, h: Int): IntArray {
        val totalPixels = w * h
        val boundaryMask = BooleanArray(totalPixels)
        val mask = IntArray(totalPixels) // 0 = 未分配/边界

        // ── 第1步：标记边界像素 ──
        // 判定标准：像素不是白色/接近白色 → 边界
        for (i in 0 until totalPixels) {
            val pixel = pixels[i]
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            val a = (pixel shr 24) and 0xFF
            // 白色或接近白色、或半透明 → 可涂色区域
            // 深色像素 → 边界线
            val isWhite = a < 128 || (r > 235 && g > 235 && b > 235)
            boundaryMask[i] = !isWhite
        }

        // ── 第2步：BFS 分配 regionId ──
        var regionId = 1
        val queue = IntArray(totalPixels)

        for (i in 0 until totalPixels) {
            if (boundaryMask[i] || mask[i] != 0) continue

            var head = 0
            var tail = 0
            queue[tail++] = i
            mask[i] = regionId

            while (head < tail) {
                val idx = queue[head++]
                val x = idx % w
                val y = idx / w

                // 4-directional neighbors
                if (x > 0) {
                    val left = idx - 1
                    if (!boundaryMask[left] && mask[left] == 0) {
                        mask[left] = regionId
                        queue[tail++] = left
                    }
                }
                if (x < w - 1) {
                    val right = idx + 1
                    if (!boundaryMask[right] && mask[right] == 0) {
                        mask[right] = regionId
                        queue[tail++] = right
                    }
                }
                if (y > 0) {
                    val up = idx - w
                    if (!boundaryMask[up] && mask[up] == 0) {
                        mask[up] = regionId
                        queue[tail++] = up
                    }
                }
                if (y < h - 1) {
                    val down = idx + w
                    if (!boundaryMask[down] && mask[down] == 0) {
                        mask[down] = regionId
                        queue[tail++] = down
                    }
                }
            }
            regionId++
        }

        return mask
    }

    // ==================================================================
    //  绘制
    // ==================================================================

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.WHITE)

        templateBitmap?.let { tmpl ->
            canvas.save()
            canvas.concat(viewMatrix)
            canvas.drawBitmap(tmpl, 0f, 0f, null)
            canvas.restore()
        }

        drawingBitmap?.let { db ->
            canvas.save()
            canvas.concat(viewMatrix)
            canvas.drawBitmap(db, 0f, 0f, null)
            canvas.restore()
        }
    }

    // ==================================================================
    //  触摸事件（含区域约束逻辑）
    // ==================================================================

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val mappedPoints = floatArrayOf(event.x, event.y)
        invertMatrix.mapPoints(mappedPoints)
        val canvasX = mappedPoints[0]
        val canvasY = mappedPoints[1]

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                when (currentTool) {
                    Tool.BRUSH, Tool.ERASER -> {
                        saveState()

                        // 区域约束：仅在画笔模式下确定当前区域
                        if (constrainToRegion && currentTool == Tool.BRUSH && regionMask != null) {
                            currentRegionId = getRegionAt(canvasX.toInt(), canvasY.toInt())
                        } else {
                            currentRegionId = -1
                        }

                        val path = Path()
                        path.moveTo(canvasX, canvasY)
                        currentPath = path

                        val selectedPaint = if (currentTool == Tool.ERASER) eraserPaint else paint
                        drawingCanvas?.drawPath(path, selectedPaint)
                        invalidate()
                    }
                    Tool.FILL -> {
                        saveState()
                        performFloodFill(canvasX.toInt(), canvasY.toInt())
                        invalidate()
                    }
                }
                lastTouchX = canvasX
                lastTouchY = canvasY
            }
            MotionEvent.ACTION_MOVE -> {
                if (currentTool == Tool.BRUSH || currentTool == Tool.ERASER) {
                    currentPath?.let { path ->
                        val midX = (lastTouchX + canvasX) / 2f
                        val midY = (lastTouchY + canvasY) / 2f
                        path.quadTo(lastTouchX, lastTouchY, midX, midY)

                        val selectedPaint = if (currentTool == Tool.ERASER) eraserPaint else paint

                        if (constrainToRegion && currentTool == Tool.BRUSH && currentRegionId > 0) {
                            drawPathConstrained(path, selectedPaint)
                        } else {
                            drawingCanvas?.drawPath(path, selectedPaint)
                        }

                        invalidate()
                        path.reset()
                        path.moveTo(midX, midY)
                    }
                    lastTouchX = canvasX
                    lastTouchY = canvasY
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                currentPath = null
                currentRegionId = -1
                redoStack.clear()
            }
        }
        return true
    }

    /**
     * 获取指定坐标的区域 ID。
     * @return 0=边界线，-1=超出范围/未分析，>=1=可涂色区域
     */
    private fun getRegionAt(x: Int, y: Int): Int {
        val mask = regionMask ?: return -1
        val w = regionWidth
        val h = regionHeight
        if (x < 0 || x >= w || y < 0 || y >= h) return -1
        return mask[y * w + x]
    }

    /**
     * 带区域约束的路径绘制。
     * 先正常绘制路径，再用区域掩码擦除溢出到其他区域的像素。
     */
    private fun drawPathConstrained(path: Path, paint: Paint) {
        val bm = drawingBitmap ?: return
        val canvas = drawingCanvas ?: return
        val mask = regionMask ?: return
        val w = regionWidth
        val h = regionHeight

        // 计算路径的包围盒
        val bounds = RectF()
        path.computeBounds(bounds, true)
        val halfStroke = paint.strokeWidth / 2f + 2f // +2 容差
        bounds.inset(-halfStroke, -halfStroke)

        val bx = bounds.left.toInt().coerceIn(0, w - 1)
        val by = bounds.top.toInt().coerceIn(0, h - 1)
        val ex = bounds.right.toInt().coerceIn(0, w - 1)
        val ey = bounds.bottom.toInt().coerceIn(0, h - 1)
        val bw = (ex - bx + 1).coerceAtLeast(1)
        val bh = (ey - by + 1).coerceAtLeast(1)

        if (bw * bh > w * h) return // 异常大，跳过约束

        // 1) 保存该区域的原始像素（用于复原溢出部分）
        val savedArea = ensureTempPixels(bw * bh)
        bm.getPixels(savedArea, 0, bw, bx, by, bw, bh)

        // 2) 正常绘制路径
        canvas.drawPath(path, paint)

        // 3) 读取绘制后的像素
        val drawnArea = IntArray(bw * bh)
        bm.getPixels(drawnArea, 0, bw, bx, by, bw, bh)

        // 4) 遍历：溢出到其他区域的像素恢复为原始值
        val regionId = currentRegionId
        var hasCorrection = false
        for (dy in 0 until bh) {
            val rowBase = (by + dy) * w + bx
            val areaIdx = dy * bw
            for (dx in 0 until bw) {
                val maskVal = mask[rowBase + dx]
                // maskVal == 0 是边界线，允许覆盖（画笔可以画在线上）
                // maskVal > 0 且 != currentRegionId → 其他区域 → 恢复
                if (maskVal > 0 && maskVal != regionId) {
                    drawnArea[areaIdx + dx] = savedArea[areaIdx + dx]
                    hasCorrection = true
                }
            }
        }

        // 5) 写回校正后的像素
        if (hasCorrection) {
            bm.setPixels(drawnArea, 0, bw, bx, by, bw, bh)
        }
    }

    /** 确保临时像素缓冲区大小足够 */
    private fun ensureTempPixels(size: Int): IntArray {
        val current = tempConstraintPixels
        if (current == null || current.size < size) {
            tempConstraintPixels = IntArray(size)
        }
        return tempConstraintPixels!!
    }

    /**
     * Flood fill 填充算法（支持区域约束）。
     */
    private fun performFloodFill(startX: Int, startY: Int) {
        val bitmap = drawingBitmap ?: return
        val w = bitmap.width
        val h = bitmap.height
        if (startX < 0 || startX >= w || startY < 0 || startY >= h) return

        val targetColor = bitmap.getPixel(startX, startY)
        val replacementColor = currentColor
        if (targetColor == replacementColor) return
        if (targetColor != android.graphics.Color.TRANSPARENT && targetColor != 0) return

        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        // 区域约束：如果启用且有区域数据，限制填充范围
        val mask = if (constrainToRegion) regionMask else null
        val restrictRegionId: Int = if (mask != null) {
            val idx = startY * w + startX
            if (idx in mask.indices) mask[idx] else -1
        } else -1

        val queue: Queue<Int> = LinkedList()
        queue.offer(startY * w + startX)

        while (queue.isNotEmpty()) {
            val idx = queue.poll() ?: continue
            val x = idx % w
            val y = idx / w

            if (x < 0 || x >= w || y < 0 || y >= h) continue
            if (pixels[idx] != targetColor) continue

            // 区域约束检查
            if (restrictRegionId > 0 && mask != null && idx in mask.indices && mask[idx] != restrictRegionId) continue

            pixels[idx] = replacementColor

            queue.offer(idx + 1)
            queue.offer(idx - 1)
            queue.offer(idx + w)
            queue.offer(idx - w)
        }

        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
    }

    // ==================================================================
    //  撤销/重做
    // ==================================================================

    private fun saveState() {
        drawingBitmap?.let { bm ->
            val copy = bm.copy(Bitmap.Config.ARGB_8888, true)
            undoStack.push(DrawAction(copy))
            if (undoStack.size > maxHistory) {
                undoStack.removeAt(0).bitmap.recycle()
            }
        }
    }

    fun undo(): Boolean {
        if (undoStack.isEmpty()) return false
        drawingBitmap?.let { bm ->
            val copy = bm.copy(Bitmap.Config.ARGB_8888, true)
            redoStack.push(DrawAction(copy))
        }
        val action = undoStack.pop()
        drawingBitmap?.let { bm ->
            val canvas = Canvas(bm)
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            canvas.drawBitmap(action.bitmap, 0f, 0f, null)
        }
        action.bitmap.recycle()
        invalidate()
        return true
    }

    fun redo(): Boolean {
        if (redoStack.isEmpty()) return false
        drawingBitmap?.let { bm ->
            val copy = bm.copy(Bitmap.Config.ARGB_8888, true)
            undoStack.push(DrawAction(copy))
        }
        val action = redoStack.pop()
        drawingBitmap?.let { bm ->
            val canvas = Canvas(bm)
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            canvas.drawBitmap(action.bitmap, 0f, 0f, null)
        }
        action.bitmap.recycle()
        invalidate()
        return true
    }

    fun clearAll() {
        saveState()
        drawingBitmap?.let { bm ->
            val canvas = Canvas(bm)
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        }
        redoStack.clear()
        invalidate()
    }

    fun canUndo(): Boolean = undoStack.isNotEmpty()
    fun canRedo(): Boolean = redoStack.isNotEmpty()

    // ==================================================================
    //  导出 & 释放
    // ==================================================================

    fun exportBitmap(): Bitmap? {
        val tmpl = templateBitmap ?: return null
        val drawBm = drawingBitmap ?: return null
        val result = Bitmap.createBitmap(tmpl.width, tmpl.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(tmpl, 0f, 0f, null)
        canvas.drawBitmap(drawBm, 0f, 0f, null)
        return result
    }

    fun release() {
        drawingBitmap?.recycle()
        drawingBitmap = null
        undoStack.forEach { it.bitmap.recycle() }
        undoStack.clear()
        redoStack.forEach { it.bitmap.recycle() }
        redoStack.clear()
        regionMask = null
        tempConstraintPixels = null
    }
}
