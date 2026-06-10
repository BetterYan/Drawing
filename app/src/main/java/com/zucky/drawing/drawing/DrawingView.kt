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

    companion object {
        // ── 边界检测阈值 ──────────────────────────
        /** 灰度低于此值→确定为边界线（深色线条核心） */
        private const val BOUNDARY_GRAY_THRESHOLD = 160
        /** 灰度高于此值→确定为可涂色区域（纯白） */
        private const val WHITE_GRAY_THRESHOLD = 235
        /** 灰度在中间区域时，8邻域内存在深色像素→判为边界（抗锯齿过渡） */
        private const val GRADIENT_THRESHOLD = 50
        /** 形态学闭运算核半径（1≈闭合≤1px断口，2≈闭合≤2px断口） */
        private const val MORPH_KERNEL_SIZE = 1
        /** 是否启用形态学闭运算修复断线 */
        private const val ENABLE_MORPH_CLOSING = true
    }

    // ── 模板 ──────────────────────────────────

    /** 模板底层线稿 */
    var templateBitmap: Bitmap? = null
        set(value) {
            field = value
            if (value != null) {
                if (drawingBitmap == null) {
                    initDrawingBitmap()
                }
                updateViewMatrix()
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

    /**
     * 底部被覆盖的高度（如浮动工具栏）。
     * 矩阵计算时从 View 总高度中扣除此值，
     * 使模板在实际可见区域内居中显示。
     */
    var visibleBottomOffset: Int = 0
        set(value) {
            if (field != value) {
                field = value
                updateViewMatrix()
                invalidate()
            }
        }

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
        updateViewMatrix()
    }

    /** 根据当前 View 尺寸和模板尺寸，重新计算缩放与居中矩阵 */
    private fun updateViewMatrix() {
        val tmpl = templateBitmap ?: return
        if (width <= 0 || height <= 0) return
        // 可见区域高度 = 总高度 - 底部工具栏遮挡
        val visibleHeight = (height - visibleBottomOffset).coerceAtLeast(1)
        val scaleX = width.toFloat() / tmpl.width
        val scaleY = visibleHeight.toFloat() / tmpl.height
        val scale = minOf(scaleX, scaleY)
        val dx = (width - tmpl.width * scale) / 2f
        // 模板居中于可见区域（整体上移半个工具栏高度）
        val dy = (height - tmpl.height * scale) / 2f - visibleBottomOffset / 2f
        viewMatrix.reset()
        viewMatrix.postScale(scale, scale)
        viewMatrix.postTranslate(dx, dy)
        viewMatrix.invert(invertMatrix)
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

    // ==================================================================
    //  区域分析 v2：梯度感知边界检测 + 形态学闭运算 + 8方向 Two-Pass CCL
    // ==================================================================

    /**
     * 同步分析区域划分（运行在后台线程）。
     *
     * 管线：
     *   1. 梯度感知边界检测 → 正确处理抗锯齿过渡像素
     *   2. 形态学闭运算（可选）→ 修复线稿断口
     *   3. 8方向 Two-Pass CCL (Union-Find) → 内存 O(w)，速度优于 BFS
     *
     * @return IntArray 每个像素的 regionId（0=边界，>=1=可涂色区域编号）
     */
    private fun analyzeRegionsSync(pixels: IntArray, w: Int, h: Int): IntArray {
        val totalPixels = w * h
        val boundaryMask = BooleanArray(totalPixels)
        val mask = IntArray(totalPixels) // 0 = 未分配/边界

        // ── 第1步：梯度感知边界检测 ──
        detectBoundariesGradientAware(pixels, w, h, boundaryMask)

        // ── 第2步：形态学闭运算修复断线 ──
        if (ENABLE_MORPH_CLOSING) {
            morphologicalClosing(boundaryMask, w, h, MORPH_KERNEL_SIZE)
        }

        // ── 第3步：8方向 Two-Pass CCL ──
        twoPassCCL(boundaryMask, w, h, mask)

        return mask
    }

    /**
     * 梯度感知边界检测。
     *
     * 三级判定：
     *   - gray < BOUNDARY_GRAY_THRESHOLD  → 必然边界（深色线条核心）
     *   - gray > WHITE_GRAY_THRESHOLD     → 必然可涂色（纯白区域）
     *   - 中间灰度 + 8邻域存在深色像素    → 边界（抗锯齿过渡区）
     *
     * 关键改进：旧的简单阈值把抗锯齿灰色像素判为"可涂色"，
     * 导致相邻区域通过灰色过渡像素连通（区域泄漏）。现在这些
     * 过渡像素被正确识别为边界。
     */
    private fun detectBoundariesGradientAware(
        pixels: IntArray, w: Int, h: Int, boundaryMask: BooleanArray
    ) {
        val totalPixels = w * h

        // 先预计算每个像素的灰度值，避免重复计算
        val grayValues = IntArray(totalPixels)
        for (i in 0 until totalPixels) {
            val pixel = pixels[i]
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            val a = (pixel shr 24) and 0xFF
            // ITU-R BT.601 亮度加权
            grayValues[i] = if (a < 128) 255 else ((r * 299 + g * 587 + b * 114) / 1000)
        }

        for (y in 0 until h) {
            for (x in 0 until w) {
                val idx = y * w + x
                val gray = grayValues[idx]

                when {
                    // 深色 → 必然边界
                    gray < BOUNDARY_GRAY_THRESHOLD -> boundaryMask[idx] = true
                    // 纯白 → 必然可涂色
                    gray > WHITE_GRAY_THRESHOLD -> boundaryMask[idx] = false
                    // 中间灰度 → 检查8邻域是否存在深色像素（抗锯齿过渡判定）
                    else -> {
                        var hasDarkNeighbor = false
                        for (dy in -1..1) {
                            val ny = y + dy
                            if (ny < 0 || ny >= h) continue
                            for (dx in -1..1) {
                                if (dx == 0 && dy == 0) continue
                                val nx = x + dx
                                if (nx < 0 || nx >= w) continue
                                if (grayValues[ny * w + nx] < BOUNDARY_GRAY_THRESHOLD) {
                                    hasDarkNeighbor = true
                                    break
                                }
                            }
                            if (hasDarkNeighbor) break
                        }
                        boundaryMask[idx] = hasDarkNeighbor
                    }
                }
            }
        }
    }

    /**
     * 形态学闭运算 = 先膨胀(Dilate) 再腐蚀(Erode)。
     *
     * 作用：闭合线稿中 ≤ kernelRadius 像素的断口，防止区域通过断口
     * 错误连通。膨胀将边界向外扩展，腐蚀再将边界缩回，结果是：
     *   - 小断口被填充（膨胀跨越断口，腐蚀时断口内已有边界标记）
     *   - 线条主体宽度基本不变
     *   - 尖锐转角略有圆化（可接受）
     */
    private fun morphologicalClosing(
        boundaryMask: BooleanArray, w: Int, h: Int, kernelRadius: Int
    ) {
        if (kernelRadius <= 0) return
        val totalPixels = w * h

        // —— Dilate: 每个边界像素将其 kernel 范围内的邻居也设为边界 ——
        val dilated = boundaryMask.copyOf()
        for (y in 0 until h) {
            for (x in 0 until w) {
                val idx = y * w + x
                if (!boundaryMask[idx]) continue

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

        // —— Erode: 只有 kernel 范围内全为边界的像素才保留 ——
        val eroded = BooleanArray(totalPixels)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val idx = y * w + x
                if (!dilated[idx]) continue // 非边界跳过

                var allBoundary = true
                for (dy in -kernelRadius..kernelRadius) {
                    val ny = y + dy
                    if (ny < 0 || ny >= h) continue
                    for (dx in -kernelRadius..kernelRadius) {
                        val nx = x + dx
                        if (nx < 0 || nx >= w) continue
                        if (!dilated[ny * w + nx]) {
                            allBoundary = false
                            break
                        }
                    }
                    if (!allBoundary) break
                }
                eroded[idx] = allBoundary
            }
        }

        // 写回
        System.arraycopy(eroded, 0, boundaryMask, 0, totalPixels)
    }

    /**
     * 8方向 Two-Pass 连通域标记 (CCL) + Union-Find 等价类合并。
     *
     * 与旧 BFS 对比：
     *   ┌────────────┬──────────────┬──────────────┐
     *   │            │ 旧 BFS (4方向)│ 新 Two-Pass  │
     *   ├────────────┼──────────────┼──────────────┤
     *   │ 连通方向    │ 4-directional │ 8-directional│
     *   │ 时间复杂度  │ O(n)          │ O(n)         │
     *   │ 额外内存    │ O(n) 队列     │ O(w) 扫描行  │
     *   │ 缓存友好    │ 差（随机跳转） │ 好（顺序扫描）│
     *   │ 对角线防漏  │ 无            │ 有            │
     *   └────────────┴──────────────┴──────────────┘
     */
    private fun twoPassCCL(
        boundaryMask: BooleanArray,
        w: Int, h: Int,
        mask: IntArray
    ) {
        val totalPixels = w * h

        // ── Union-Find 结构 ──
        // parent[0] 不用，label 从 1 开始
        var parent = IntArray(256) { it }
        var parentCap = 256

        fun ensureCap(label: Int) {
            if (label >= parentCap) {
                val newCap = parentCap * 2
                parent = parent.copyOf(newCap)
                for (i in parentCap until newCap) parent[i] = i
                parentCap = newCap
            }
        }

        fun find(x: Int): Int {
            var r = x
            while (parent[r] != r) {
                parent[r] = parent[parent[r]] // 路径压缩
                r = parent[r]
            }
            return r
        }

        fun union(a: Int, b: Int) {
            val ra = find(a)
            val rb = find(b)
            if (ra != rb) {
                // 较小 ID 作为根（保持标签连续性）
                if (ra < rb) parent[rb] = ra else parent[ra] = rb
            }
        }

        // ── Pass 1: 行扫描 + 8方向标签传播 ──
        // 栈上分配的小数组避免每像素创建 ArrayList（对 1080p 图片约省 200 万次分配）
        val neighborLabels = IntArray(4)
        var nextLabel = 1

        for (y in 0 until h) {
            for (x in 0 until w) {
                val idx = y * w + x
                if (boundaryMask[idx]) continue

                // 收集已扫描的 4 个 8-邻域标签: W, NW, N, NE
                var nc = 0

                // West (x-1, y)
                if (x > 0 && !boundaryMask[idx - 1]) {
                    val lb = mask[idx - 1]
                    if (lb > 0) neighborLabels[nc++] = lb
                }
                // Northwest (x-1, y-1)
                if (x > 0 && y > 0 && !boundaryMask[idx - w - 1]) {
                    val lb = mask[idx - w - 1]
                    if (lb > 0) neighborLabels[nc++] = lb
                }
                // North (x, y-1)
                if (y > 0 && !boundaryMask[idx - w]) {
                    val lb = mask[idx - w]
                    if (lb > 0) neighborLabels[nc++] = lb
                }
                // Northeast (x+1, y-1)
                if (x < w - 1 && y > 0 && !boundaryMask[idx - w + 1]) {
                    val lb = mask[idx - w + 1]
                    if (lb > 0) neighborLabels[nc++] = lb
                }

                if (nc == 0) {
                    // 新区域
                    ensureCap(nextLabel)
                    mask[idx] = nextLabel
                    nextLabel++
                } else {
                    // 取最小标签，合并等价标签
                    var minLabel = neighborLabels[0]
                    for (i in 1 until nc) {
                        if (neighborLabels[i] < minLabel) minLabel = neighborLabels[i]
                    }
                    mask[idx] = minLabel
                    for (i in 0 until nc) {
                        val nl = neighborLabels[i]
                        if (nl != minLabel) union(minLabel, nl)
                    }
                }
            }
        }

        // ── Pass 2: 标签统一化（Find 压缩） ──
        for (i in 0 until totalPixels) {
            if (mask[i] > 0) {
                mask[i] = find(mask[i])
            }
        }
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
                            // 触摸到边界线（regionId == 0）时，不执行绘制
                            if (currentRegionId == 0) {
                                currentPath = null
                                return true
                            }
                        } else {
                            currentRegionId = -1
                        }

                        val path = Path()
                        path.moveTo(canvasX, canvasY)
                        currentPath = path

                        val selectedPaint = if (currentTool == Tool.ERASER) eraserPaint else paint
                        if (constrainToRegion && currentTool == Tool.BRUSH && currentRegionId > 0) {
                            drawPathConstrained(path, selectedPaint)
                        } else {
                            drawingCanvas?.drawPath(path, selectedPaint)
                        }
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
                        } else if (!constrainToRegion || currentTool == Tool.ERASER) {
                            drawingCanvas?.drawPath(path, selectedPaint)
                        }
                        // else: constrainToRegion == true && currentRegionId <= 0 → 不绘制（边界线/无效区域）

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
                // maskVal == 0 → 边界线 → 恢复（轮廓线不应被涂色覆盖）
                // maskVal > 0 且 != currentRegionId → 其他区域 → 恢复
                if (maskVal != regionId) {
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
