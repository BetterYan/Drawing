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
 * - 橡皮擦
 * - 填充（flood fill）
 * - 撤销/重做
 */
class DrawingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // 模板（底层线稿）
    var templateBitmap: Bitmap? = null
        set(value) {
            field = value
            if (value != null && drawingBitmap == null) {
                initDrawingBitmap()
            }
            invalidate()
        }

    // 绘图层
    private var drawingBitmap: Bitmap? = null
    private var drawingCanvas: Canvas? = null

    // 画笔
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

    // 工具状态
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

    // 路径历史（撤销/重做）
    private data class DrawAction(
        val bitmap: Bitmap  // 保存整个绘制层快照
    )

    private val undoStack = Stack<DrawAction>()
    private val redoStack = Stack<DrawAction>()
    private val maxHistory = 30

    // 当前绘制路径
    private var currentPath: Path? = null

    // 缩放/平移矩阵
    private val viewMatrix = Matrix()
    private val invertMatrix = Matrix()

    private var lastTouchX = 0f
    private var lastTouchY = 0f

    fun initDrawingBitmap() {
        val w = templateBitmap?.width ?: width
        val h = templateBitmap?.height ?: height
        if (w <= 0 || h <= 0) return

        drawingBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        drawingCanvas = Canvas(drawingBitmap!!)
        // 初始透明，让模板完全显示
        undoStack.clear()
        redoStack.clear()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (drawingBitmap == null) {
            initDrawingBitmap()
        }
        // 计算适配矩阵
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

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.WHITE)

        // 绘制模板（底层）
        templateBitmap?.let { tmpl ->
            canvas.save()
            canvas.concat(viewMatrix)
            canvas.drawBitmap(tmpl, 0f, 0f, null)
            canvas.restore()
        }

        // 绘制用户绘画层
        drawingBitmap?.let { db ->
            canvas.save()
            canvas.concat(viewMatrix)
            canvas.drawBitmap(db, 0f, 0f, null)
            canvas.restore()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val touchX = event.x
        val touchY = event.y

        // 转换触摸坐标到画布坐标
        val mappedPoints = floatArrayOf(touchX, touchY)
        invertMatrix.mapPoints(mappedPoints)
        val canvasX = mappedPoints[0]
        val canvasY = mappedPoints[1]

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                when (currentTool) {
                    Tool.BRUSH, Tool.ERASER -> {
                        saveState()
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
                        path.quadTo(lastTouchX, lastTouchY, (lastTouchX + canvasX) / 2f, (lastTouchY + canvasY) / 2f)
                        // 在绘制层上绘制
                        val selectedPaint = if (currentTool == Tool.ERASER) eraserPaint else paint
                        drawingCanvas?.drawPath(path, selectedPaint)
                        invalidate()
                        path.reset()
                        path.moveTo((lastTouchX + canvasX) / 2f, (lastTouchY + canvasY) / 2f)
                    }
                    lastTouchX = canvasX
                    lastTouchY = canvasY
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                currentPath = null
                redoStack.clear()
            }
        }
        return true
    }

    /**
     * Flood fill 填充算法
     */
    private fun performFloodFill(startX: Int, startY: Int) {
        val bitmap = drawingBitmap ?: return
        if (startX < 0 || startX >= bitmap.width || startY < 0 || startY >= bitmap.height) return

        val targetColor = bitmap.getPixel(startX, startY)
        val replacementColor = currentColor

        // 如果目标颜色和替换颜色相同，跳过
        if (targetColor == replacementColor) return

        // 如果点击位置已经有模板的颜色（非透明），跳过
        // 只在透明区域填充
        if (targetColor != Color.TRANSPARENT && targetColor != 0) return

        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

        val width = bitmap.width
        val height = bitmap.height

        // BFS flood fill
        val queue: Queue<Int> = LinkedList()
        queue.offer(startY * width + startX)

        while (queue.isNotEmpty()) {
            val idx = queue.poll() ?: continue
            val x = idx % width
            val y = idx / width

            if (x < 0 || x >= width || y < 0 || y >= height) continue
            if (pixels[idx] != targetColor) continue

            pixels[idx] = replacementColor

            queue.offer(idx + 1)        // right
            queue.offer(idx - 1)        // left
            queue.offer(idx + width)    // down
            queue.offer(idx - width)    // up
        }

        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
    }

    /**
     * 保存当前状态到撤销栈
     */
    private fun saveState() {
        drawingBitmap?.let { bm ->
            val copy = bm.copy(Bitmap.Config.ARGB_8888, true)
            undoStack.push(DrawAction(copy))
            if (undoStack.size > maxHistory) {
                undoStack.removeAt(0)?.bitmap?.recycle()
            }
        }
    }

    /**
     * 撤销
     */
    fun undo(): Boolean {
        if (undoStack.isEmpty()) return false

        // 保存当前状态到重做栈
        drawingBitmap?.let { bm ->
            val copy = bm.copy(Bitmap.Config.ARGB_8888, true)
            redoStack.push(DrawAction(copy))
        }

        // 恢复上一个状态
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

    /**
     * 重做
     */
    fun redo(): Boolean {
        if (redoStack.isEmpty()) return false

        // 保存当前状态到撤销栈
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

    /**
     * 清空所有绘制
     */
    fun clearAll() {
        saveState()
        drawingBitmap?.let { bm ->
            val canvas = Canvas(bm)
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        }
        redoStack.clear()
        invalidate()
    }

    /**
     * 是否有可撤销的操作
     */
    fun canUndo(): Boolean = undoStack.isNotEmpty()

    /**
     * 是否有可重做的操作
     */
    fun canRedo(): Boolean = redoStack.isNotEmpty()

    /**
     * 导出最终图片（模板 + 绘画层合成）
     */
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

    /**
     * 释放资源
     */
    fun release() {
        drawingBitmap?.recycle()
        drawingBitmap = null
        undoStack.forEach { it.bitmap.recycle() }
        undoStack.clear()
        redoStack.forEach { it.bitmap.recycle() }
        redoStack.clear()
    }
}
