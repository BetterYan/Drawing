package com.zucky.drawing.model

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/**
 * 绘图模板数据类
 */
data class Template(
    val id: String,
    val name: String,
    val emoji: String,
    val source: TemplateSource
)

sealed class TemplateSource {
    /** 内置程序化生成的模板 */
    data class BuiltIn(val generatorId: Int) : TemplateSource()
    /** 从 assets 文件夹加载的图片模板 */
    data class AssetImage(val assetPath: String) : TemplateSource()
}

/**
 * 模板生成器：生成可爱的线稿模板
 */
object TemplateGenerator {

    /** 内置模板列表 */
    val builtInTemplates = listOf(
        Template("star", "小星星", "⭐", TemplateSource.BuiltIn(0)),
        Template("heart", "爱心", "❤️", TemplateSource.BuiltIn(1)),
        Template("flower", "小花花", "🌸", TemplateSource.BuiltIn(2)),
        Template("house", "小房子", "🏠", TemplateSource.BuiltIn(3)),
        Template("cat", "小猫咪", "🐱", TemplateSource.BuiltIn(4)),
        Template("fish", "小鱼儿", "🐟", TemplateSource.BuiltIn(5)),
        Template("butterfly", "小蝴蝶", "🦋", TemplateSource.BuiltIn(6)),
        Template("sun", "太阳公公", "☀️", TemplateSource.BuiltIn(7)),
        Template("moon", "月亮姐姐", "🌙", TemplateSource.BuiltIn(8)),
        Template("icecream", "冰淇淋", "🍦", TemplateSource.BuiltIn(9)),
        Template("tree", "小树", "🌳", TemplateSource.BuiltIn(10)),
        Template("car", "小汽车", "🚗", TemplateSource.BuiltIn(11)),
    )

    private val outlinePaint = Paint().apply {
        color = android.graphics.Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 6f
        isAntiAlias = true
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    /** 模板主题填充色（预览模式使用） */
    private val templateColors = mapOf(
        0 to android.graphics.Color.rgb(255, 215, 0),    // ⭐ 星星 - 金色
        1 to android.graphics.Color.rgb(255, 105, 180),  // ❤️ 爱心 - 粉红
        2 to android.graphics.Color.rgb(255, 182, 193),  // 🌸 小花花 - 浅粉
        3 to android.graphics.Color.rgb(210, 180, 140),  // 🏠 小房子 - 浅棕
        4 to android.graphics.Color.rgb(189, 189, 189),  // 🐱 小猫咪 - 浅灰
        5 to android.graphics.Color.rgb(135, 206, 235),  // 🐟 小鱼儿 - 浅蓝
        6 to android.graphics.Color.rgb(216, 191, 216),  // 🦋 小蝴蝶 - 淡紫
        7 to android.graphics.Color.rgb(255, 218, 185),  // ☀️ 太阳公公 - 蜜桃
        8 to android.graphics.Color.rgb(255, 255, 224),  // 🌙 月亮姐姐 - 淡黄
        9 to android.graphics.Color.rgb(255, 218, 185),  // 🍦 冰淇淋 - 蜜桃
        10 to android.graphics.Color.rgb(144, 238, 144), // 🌳 小树 - 浅绿
        11 to android.graphics.Color.rgb(255, 99, 71),   // 🚗 小汽车 - 番茄红
    )

    private fun createFillPaint(color: Int) = Paint().apply {
        this.color = color
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    /** 当前绘制使用的填充色（null = 只画线稿） */
    private var currentFillColor: Int? = null

    /**
     * 生成指定 ID 的模板 Bitmap（纯线稿，用于 DrawingView 区域分析）
     * @param width  画布宽度（像素）
     * @param height 画布高度（像素）
     */
    fun generateTemplate(generatorId: Int, width: Int = 1024, height: Int = 1024): Bitmap {
        return drawTemplateInternal(generatorId, width, height, withFill = false)
    }

    /**
     * 生成指定 ID 的彩色预览 Bitmap（用于画廊缩略图展示）
     */
    fun generatePreview(generatorId: Int, size: Int = 256): Bitmap {
        return drawTemplateInternal(generatorId, size, size, withFill = true)
    }

    private fun drawTemplateInternal(generatorId: Int, width: Int, height: Int, withFill: Boolean): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // 白色背景
        canvas.drawColor(android.graphics.Color.WHITE)

        // 设置填充色
        currentFillColor = if (withFill) templateColors[generatorId] else null

        // 使用较小维度的 80% 作为绘图比例基准，确保图案完整居中显示
        val size = minOf(width, height) * 0.8f
        val cx = width / 2f
        val cy = height / 2f
        val margin = size * 0.15f

        when (generatorId) {
            0 -> drawStar(canvas, cx, cy, size * 0.35f)
            1 -> drawHeart(canvas, cx, cy + size * 0.05f, size * 0.32f)
            2 -> drawFlower(canvas, cx, cy, size * 0.3f)
            3 -> drawHouse(canvas, cx, cy, size.toFloat())
            4 -> drawCat(canvas, cx, cy, size * 0.35f)
            5 -> drawFish(canvas, cx, cy, size * 0.35f)
            6 -> drawButterfly(canvas, cx, cy, size * 0.3f)
            7 -> drawSun(canvas, cx, cy, size * 0.35f)
            8 -> drawMoon(canvas, cx, cy, size * 0.3f)
            9 -> drawIceCream(canvas, cx, cy + size * 0.05f, size.toFloat())
            10 -> drawTree(canvas, cx, cy, size.toFloat())
            11 -> drawCar(canvas, cx, cy + size * 0.05f, size * 0.4f)
        }

        currentFillColor = null
        return bitmap
    }

    // ── 填充辅助 ────────────────────────────────

    private fun Canvas.fillPath(path: Path) {
        currentFillColor?.let { drawPath(path, createFillPaint(it)) }
    }

    private fun Canvas.fillCircle(cx: Float, cy: Float, radius: Float) {
        currentFillColor?.let { drawCircle(cx, cy, radius, createFillPaint(it)) }
    }

    private fun Canvas.fillRect(rect: RectF) {
        currentFillColor?.let { drawRect(rect, createFillPaint(it)) }
    }

    // ── 各模板绘制方法 ──────────────────────────

    private fun drawStar(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val path = Path()
        val innerR = r * 0.4f
        for (i in 0 until 5) {
            val outerAngle = (Math.toRadians(-90.0 + i * 72.0))
            val innerAngle = (Math.toRadians(-90.0 + 36.0 + i * 72.0))
            if (i == 0) {
                path.moveTo(cx + r * Math.cos(outerAngle).toFloat(), cy + r * Math.sin(outerAngle).toFloat())
            } else {
                path.lineTo(cx + r * Math.cos(outerAngle).toFloat(), cy + r * Math.sin(outerAngle).toFloat())
            }
            path.lineTo(cx + innerR * Math.cos(innerAngle).toFloat(), cy + innerR * Math.sin(innerAngle).toFloat())
        }
        path.close()
        canvas.fillPath(path)
        canvas.drawPath(path, outlinePaint)
    }

    private fun drawHeart(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val path = Path()
        path.moveTo(cx, cy + r * 0.7f)
        // Left curve
        path.cubicTo(cx - r * 1.4f, cy - r * 0.1f, cx - r * 0.5f, cy - r * 0.8f, cx, cy - r * 0.2f)
        // Right curve
        path.cubicTo(cx + r * 0.5f, cy - r * 0.8f, cx + r * 1.4f, cy - r * 0.1f, cx, cy + r * 0.7f)
        path.close()
        canvas.fillPath(path)
        canvas.drawPath(path, outlinePaint)
    }

    private fun drawFlower(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        // Petals
        for (i in 0 until 6) {
            val angle = Math.toRadians(i * 60.0)
            val px = cx + r * 0.55f * Math.cos(angle).toFloat()
            val py = cy + r * 0.55f * Math.sin(angle).toFloat()
            canvas.fillCircle(px, py, r * 0.3f)
            canvas.drawCircle(px, py, r * 0.3f, outlinePaint)
        }
        // Center
        canvas.fillCircle(cx, cy, r * 0.25f)
        canvas.drawCircle(cx, cy, r * 0.25f, outlinePaint)
        // Stem
        outlinePaint.strokeWidth = 8f
        canvas.drawLine(cx, cy + r, cx, cy + r * 1.5f, outlinePaint)
        // Leaves
        val leafPaint = Paint(outlinePaint).apply { strokeWidth = 6f }
        canvas.drawLine(cx, cy + r * 1.2f, cx + r * 0.4f, cy + r * 1.0f, leafPaint)
        canvas.drawLine(cx, cy + r * 1.2f, cx - r * 0.4f, cy + r * 1.0f, leafPaint)
        outlinePaint.strokeWidth = 6f
    }

    private fun drawHouse(canvas: Canvas, cx: Float, cy: Float, size: Float) {
        val w = size * 0.5f
        val h = size * 0.35f
        val left = cx - w
        val top = cy - h * 0.3f
        val right = cx + w
        val bottom = cy + h

        // Roof (triangle)
        val roofPath = Path()
        roofPath.moveTo(cx, top - h * 0.4f)
        roofPath.lineTo(left - w * 0.1f, top)
        roofPath.lineTo(right + w * 0.1f, top)
        roofPath.close()
        outlinePaint.strokeWidth = 6f
        canvas.fillPath(roofPath)
        canvas.drawPath(roofPath, outlinePaint)

        // House body
        canvas.fillRect(RectF(left, top, right, bottom))
        canvas.drawRect(RectF(left, top, right, bottom), outlinePaint)

        // Door
        val doorW = w * 0.3f
        val doorH = h * 0.5f
        canvas.fillRect(RectF(cx - doorW / 2, bottom - doorH, cx + doorW / 2, bottom))
        canvas.drawRect(RectF(cx - doorW / 2, bottom - doorH, cx + doorW / 2, bottom), outlinePaint)

        // Window
        val winSize = w * 0.2f
        canvas.fillRect(RectF(cx + w * 0.2f, top + h * 0.15f, cx + w * 0.2f + winSize, top + h * 0.15f + winSize))
        canvas.drawRect(RectF(cx + w * 0.2f, top + h * 0.15f, cx + w * 0.2f + winSize, top + h * 0.15f + winSize), outlinePaint)
        val winPaint = Paint(outlinePaint).apply { strokeWidth = 3f }
        canvas.drawLine(cx + w * 0.2f + winSize / 2, top + h * 0.15f, cx + w * 0.2f + winSize / 2, top + h * 0.15f + winSize, winPaint)
        canvas.drawLine(cx + w * 0.2f, top + h * 0.15f + winSize / 2, cx + w * 0.2f + winSize, top + h * 0.15f + winSize / 2, winPaint)

        // Chimney
        canvas.fillRect(RectF(cx + w * 0.3f, top - h * 0.5f, cx + w * 0.5f, top - h * 0.1f))
        canvas.drawRect(RectF(cx + w * 0.3f, top - h * 0.5f, cx + w * 0.5f, top - h * 0.1f), outlinePaint)
    }

    private fun drawCat(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        // Face
        canvas.fillCircle(cx, cy, r)
        canvas.drawCircle(cx, cy, r, outlinePaint)
        // Ears
        val earPath = Path()
        earPath.moveTo(cx - r * 0.7f, cy - r * 0.5f)
        earPath.lineTo(cx - r * 0.9f, cy - r * 1.3f)
        earPath.lineTo(cx - r * 0.2f, cy - r * 0.7f)
        earPath.close()
        canvas.fillPath(earPath)
        canvas.drawPath(earPath, outlinePaint)

        val earPathR = Path()
        earPathR.moveTo(cx + r * 0.7f, cy - r * 0.5f)
        earPathR.lineTo(cx + r * 0.9f, cy - r * 1.3f)
        earPathR.lineTo(cx + r * 0.2f, cy - r * 0.7f)
        earPathR.close()
        canvas.fillPath(earPathR)
        canvas.drawPath(earPathR, outlinePaint)

        // Eyes
        canvas.drawCircle(cx - r * 0.25f, cy - r * 0.1f, r * 0.08f, outlinePaint)
        canvas.drawCircle(cx + r * 0.25f, cy - r * 0.1f, r * 0.08f, outlinePaint)

        // Nose
        val nosePath = Path()
        nosePath.moveTo(cx, cy + r * 0.05f)
        nosePath.lineTo(cx - r * 0.06f, cy + r * 0.12f)
        nosePath.lineTo(cx + r * 0.06f, cy + r * 0.12f)
        nosePath.close()
        canvas.drawPath(nosePath, outlinePaint)

        // Mouth
        val mouthPaint = Paint(outlinePaint).apply { strokeWidth = 3f }
        canvas.drawLine(cx, cy + r * 0.12f, cx - r * 0.12f, cy + r * 0.25f, mouthPaint)
        canvas.drawLine(cx, cy + r * 0.12f, cx + r * 0.12f, cy + r * 0.25f, mouthPaint)

        // Whiskers
        canvas.drawLine(cx - r * 0.5f, cy + r * 0.05f, cx - r * 0.15f, cy + r * 0.1f, mouthPaint)
        canvas.drawLine(cx - r * 0.5f, cy + r * 0.15f, cx - r * 0.15f, cy + r * 0.15f, mouthPaint)
        canvas.drawLine(cx + r * 0.5f, cy + r * 0.05f, cx + r * 0.15f, cy + r * 0.1f, mouthPaint)
        canvas.drawLine(cx + r * 0.5f, cy + r * 0.15f, cx + r * 0.15f, cy + r * 0.15f, mouthPaint)
    }

    private fun drawFish(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        // Body
        val bodyPath = Path()
        bodyPath.moveTo(cx - r * 0.9f, cy)
        bodyPath.cubicTo(cx - r * 0.3f, cy - r * 0.6f, cx + r * 0.5f, cy - r * 0.3f, cx + r * 0.6f, cy)
        bodyPath.cubicTo(cx + r * 0.5f, cy + r * 0.3f, cx - r * 0.3f, cy + r * 0.6f, cx - r * 0.9f, cy)
        bodyPath.close()
        canvas.fillPath(bodyPath)
        canvas.drawPath(bodyPath, outlinePaint)

        // Tail
        val tailPath = Path()
        tailPath.moveTo(cx + r * 0.55f, cy)
        tailPath.lineTo(cx + r * 1.1f, cy - r * 0.5f)
        tailPath.lineTo(cx + r * 1.1f, cy + r * 0.5f)
        tailPath.close()
        canvas.fillPath(tailPath)
        canvas.drawPath(tailPath, outlinePaint)

        // Eye
        canvas.drawCircle(cx - r * 0.2f, cy - r * 0.1f, r * 0.08f, outlinePaint)

        // Smile
        val smilePaint = Paint(outlinePaint).apply { strokeWidth = 3f }
        canvas.drawLine(cx - r * 0.05f, cy + r * 0.1f, cx + r * 0.15f, cy + r * 0.15f, smilePaint)
    }

    private fun drawButterfly(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        // Body (line)
        outlinePaint.strokeWidth = 4f
        canvas.drawLine(cx, cy - r * 0.9f, cx, cy + r * 0.9f, outlinePaint)
        outlinePaint.strokeWidth = 6f

        // Top wings
        val topLeft = Path()
        topLeft.moveTo(cx, cy - r * 0.3f)
        topLeft.cubicTo(cx - r * 0.6f, cy - r * 1.2f, cx - r * 1.0f, cy - r, cx - r * 0.3f, cy - r * 0.2f)
        topLeft.close()
        canvas.fillPath(topLeft)
        canvas.drawPath(topLeft, outlinePaint)

        val topRight = Path()
        topRight.moveTo(cx, cy - r * 0.3f)
        topRight.cubicTo(cx + r * 0.6f, cy - r * 1.2f, cx + r * 1.0f, cy - r, cx + r * 0.3f, cy - r * 0.2f)
        topRight.close()
        canvas.fillPath(topRight)
        canvas.drawPath(topRight, outlinePaint)

        // Bottom wings
        val bottomLeft = Path()
        bottomLeft.moveTo(cx, cy - r * 0.1f)
        bottomLeft.cubicTo(cx - r * 0.5f, cy + r * 0.3f, cx - r * 0.7f, cy + r * 0.8f, cx - r * 0.2f, cy + r * 0.3f)
        bottomLeft.close()
        canvas.fillPath(bottomLeft)
        canvas.drawPath(bottomLeft, outlinePaint)

        val bottomRight = Path()
        bottomRight.moveTo(cx, cy - r * 0.1f)
        bottomRight.cubicTo(cx + r * 0.5f, cy + r * 0.3f, cx + r * 0.7f, cy + r * 0.8f, cx + r * 0.2f, cy + r * 0.3f)
        bottomRight.close()
        canvas.fillPath(bottomRight)
        canvas.drawPath(bottomRight, outlinePaint)

        // Antennae
        val antPaint = Paint(outlinePaint).apply { strokeWidth = 3f }
        canvas.drawCircle(cx - r * 0.15f, cy - r * 1.0f, r * 0.05f, antPaint)
        canvas.drawCircle(cx + r * 0.15f, cy - r * 1.0f, r * 0.05f, antPaint)
        canvas.drawLine(cx - r * 0.1f, cy - r * 0.9f, cx - r * 0.15f, cy - r * 0.96f, antPaint)
        canvas.drawLine(cx + r * 0.1f, cy - r * 0.9f, cx + r * 0.15f, cy - r * 0.96f, antPaint)
    }

    private fun drawSun(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        // Center circle
        canvas.fillCircle(cx, cy, r * 0.5f)
        canvas.drawCircle(cx, cy, r * 0.5f, outlinePaint)

        // Rays
        val rayPaint = Paint(outlinePaint).apply { strokeWidth = 5f }
        for (i in 0 until 12) {
            val angle = Math.toRadians(i * 30.0)
            val startX = cx + r * 0.6f * Math.cos(angle).toFloat()
            val startY = cy + r * 0.6f * Math.sin(angle).toFloat()
            val endX = cx + r * 1.0f * Math.cos(angle).toFloat()
            val endY = cy + r * 1.0f * Math.sin(angle).toFloat()
            canvas.drawLine(startX, startY, endX, endY, rayPaint)
        }

        // Face
        val facePaint = Paint(outlinePaint).apply { strokeWidth = 3f }
        // Eyes
        canvas.drawCircle(cx - r * 0.18f, cy - r * 0.08f, r * 0.06f, facePaint)
        canvas.drawCircle(cx + r * 0.18f, cy - r * 0.08f, r * 0.06f, facePaint)
        // Smile
        val smilePath = Path()
        smilePath.addArc(cx - r * 0.15f, cy + r * 0.05f, cx + r * 0.15f, cy + r * 0.25f, 20f, 140f)
        canvas.drawPath(smilePath, facePaint)
    }

    private fun drawMoon(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        // Crescent moon
        val path = Path()
        path.addCircle(cx, cy, r, Path.Direction.CW)
        path.addCircle(cx + r * 0.4f, cy - r * 0.1f, r * 0.75f, Path.Direction.CCW)
        canvas.fillPath(path)
        canvas.drawPath(path, outlinePaint)

        // Small stars around
        val starPaint = Paint(outlinePaint).apply { strokeWidth = 3f }
        fun drawSmallStar(x: Float, y: Float, sr: Float) {
            val sp = Path()
            for (i in 0 until 5) {
                val oa = Math.toRadians(-90.0 + i * 72.0)
                val ia = Math.toRadians(-90.0 + 36.0 + i * 72.0)
                if (i == 0) sp.moveTo(x + sr * Math.cos(oa).toFloat(), y + sr * Math.sin(oa).toFloat())
                else sp.lineTo(x + sr * Math.cos(oa).toFloat(), y + sr * Math.sin(oa).toFloat())
                sp.lineTo(x + sr * 0.4f * Math.cos(ia).toFloat(), y + sr * 0.4f * Math.sin(ia).toFloat())
            }
            sp.close()
            canvas.drawPath(sp, starPaint)
        }
        drawSmallStar(cx + r * 0.9f, cy - r * 0.6f, r * 0.1f)
        drawSmallStar(cx - r * 0.5f, cy - r * 0.7f, r * 0.07f)
        drawSmallStar(cx - r * 0.8f, cy + r * 0.2f, r * 0.08f)
    }

    private fun drawIceCream(canvas: Canvas, cx: Float, cy: Float, size: Float) {
        // Cone
        val conePath = Path()
        conePath.moveTo(cx - size * 0.13f, cy - size * 0.05f)
        conePath.lineTo(cx, cy + size * 0.35f)
        conePath.lineTo(cx + size * 0.13f, cy - size * 0.05f)
        conePath.close()
        canvas.fillPath(conePath)
        canvas.drawPath(conePath, outlinePaint)

        // Cone cross lines
        val conePaint = Paint(outlinePaint).apply { strokeWidth = 3f }
        canvas.drawLine(cx - size * 0.08f, cy + size * 0.0f, cx + size * 0.08f, cy + size * 0.0f, conePaint)
        canvas.drawLine(cx - size * 0.04f, cy + size * 0.15f, cx + size * 0.04f, cy + size * 0.15f, conePaint)

        // Scoops
        canvas.fillCircle(cx, cy - size * 0.1f, size * 0.18f)
        canvas.drawCircle(cx, cy - size * 0.1f, size * 0.18f, outlinePaint)
        canvas.fillCircle(cx, cy - size * 0.25f, size * 0.15f)
        canvas.drawCircle(cx, cy - size * 0.25f, size * 0.15f, outlinePaint)

        // Cherry on top
        canvas.fillCircle(cx, cy - size * 0.38f, size * 0.06f)
        canvas.drawCircle(cx, cy - size * 0.38f, size * 0.06f, outlinePaint)
        val cherryPaint = Paint(outlinePaint).apply { strokeWidth = 2f }
        canvas.drawLine(cx, cy - size * 0.33f, cx, cy - size * 0.4f, cherryPaint)
    }

    private fun drawTree(canvas: Canvas, cx: Float, cy: Float, size: Float) {
        // Trunk
        val trunkW = size * 0.08f
        outlinePaint.strokeWidth = 8f
        canvas.fillRect(RectF(cx - trunkW, cy + size * 0.1f, cx + trunkW, cy + size * 0.4f))
        canvas.drawRect(RectF(cx - trunkW, cy + size * 0.1f, cx + trunkW, cy + size * 0.4f), outlinePaint)
        outlinePaint.strokeWidth = 6f

        // Leaf circles (three circles overlapping)
        canvas.fillCircle(cx, cy - size * 0.05f, size * 0.22f)
        canvas.drawCircle(cx, cy - size * 0.05f, size * 0.22f, outlinePaint)
        canvas.fillCircle(cx - size * 0.15f, cy + size * 0.0f, size * 0.18f)
        canvas.drawCircle(cx - size * 0.15f, cy + size * 0.0f, size * 0.18f, outlinePaint)
        canvas.fillCircle(cx + size * 0.15f, cy + size * 0.0f, size * 0.18f)
        canvas.drawCircle(cx + size * 0.15f, cy + size * 0.0f, size * 0.18f, outlinePaint)
    }

    private fun drawCar(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        // Body
        val bodyPath = Path()
        bodyPath.moveTo(cx - r, cy)
        bodyPath.lineTo(cx - r * 0.8f, cy)
        bodyPath.lineTo(cx - r * 0.5f, cy - r * 0.3f)
        bodyPath.lineTo(cx + r * 0.3f, cy - r * 0.3f)
        bodyPath.lineTo(cx + r * 0.6f, cy)
        bodyPath.lineTo(cx + r, cy)
        bodyPath.lineTo(cx + r, cy + r * 0.2f)
        bodyPath.lineTo(cx - r, cy + r * 0.2f)
        bodyPath.close()
        canvas.fillPath(bodyPath)
        canvas.drawPath(bodyPath, outlinePaint)

        // Windows
        val winPaint = Paint(outlinePaint).apply { strokeWidth = 4f }
        val winPath = Path()
        winPath.moveTo(cx - r * 0.45f, cy - r * 0.25f)
        winPath.lineTo(cx - r * 0.15f, cy - r * 0.25f)
        winPath.lineTo(cx + r * 0.05f, cy)
        winPath.lineTo(cx - r * 0.55f, cy)
        winPath.close()
        canvas.drawPath(winPath, winPaint)

        val winPath2 = Path()
        winPath2.moveTo(cx + r * 0.25f, cy - r * 0.25f)
        winPath2.lineTo(cx + r * 0.05f, cy)
        winPath2.lineTo(cx + r * 0.35f, cy)
        winPath2.close()
        canvas.drawPath(winPath2, winPaint)

        // Wheels
        // 轮子用深灰色填充
        val wheelFillColor = currentFillColor
        currentFillColor = android.graphics.Color.rgb(80, 80, 80)
        canvas.fillCircle(cx - r * 0.55f, cy + r * 0.25f, r * 0.18f)
        canvas.fillCircle(cx + r * 0.55f, cy + r * 0.25f, r * 0.18f)
        currentFillColor = wheelFillColor
        canvas.drawCircle(cx - r * 0.55f, cy + r * 0.25f, r * 0.18f, outlinePaint)
        canvas.drawCircle(cx + r * 0.55f, cy + r * 0.25f, r * 0.18f, outlinePaint)
        canvas.drawCircle(cx - r * 0.55f, cy + r * 0.25f, r * 0.05f, outlinePaint)
        canvas.drawCircle(cx + r * 0.55f, cy + r * 0.25f, r * 0.05f, outlinePaint)
    }
}
