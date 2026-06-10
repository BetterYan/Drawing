package com.zucky.drawing.drawing

import android.content.ContentValues
import android.content.Context
import android.content.DialogInterface
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.view.animation.OvershootInterpolator
import com.zucky.drawing.TRANSITION_OPEN
import com.zucky.drawing.TRANSITION_CLOSE
import android.widget.Toast
import com.zucky.drawing.applyTransition
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.zucky.drawing.R
import com.zucky.drawing.databinding.ActivityDrawingBinding
import com.zucky.drawing.model.TemplateGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class DrawingActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TEMPLATE_ID = "template_id"
        const val EXTRA_TEMPLATE_NAME = "template_name"
        const val EXTRA_TEMPLATE_SOURCE_TYPE = "template_source_type"
        const val EXTRA_TEMPLATE_GENERATOR_ID = "template_generator_id"
        const val EXTRA_TEMPLATE_ASSET_PATH = "template_asset_path"
    }

    private lateinit var binding: ActivityDrawingBinding
    private var selectedColorView: View? = null

    // 颜色列表
    private val colorPalette = listOf(
        R.color.draw_red, R.color.draw_pink, R.color.draw_purple,
        R.color.draw_deep_purple, R.color.draw_indigo, R.color.draw_blue,
        R.color.draw_light_blue, R.color.draw_cyan, R.color.draw_teal,
        R.color.draw_green, R.color.draw_light_green, R.color.draw_lime,
        R.color.draw_yellow, R.color.draw_amber, R.color.draw_orange,
        R.color.draw_deep_orange, R.color.draw_brown, R.color.draw_grey,
        R.color.draw_black, R.color.draw_white
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDrawingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        loadTemplate()
        setupColorPalette()
        setupToolButtons()
        setupBrushSize()
        setupActionButtons()
        setupRegionLock()

        // 底部工具栏高度变化时，同步给 DrawingView 以修正模板居中位置
        binding.root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            binding.drawingView.visibleBottomOffset = binding.bottomToolbar.height
        }

        // 拦截系统返回（含手势返回），弹出确认对话框
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                showDiscardConfirmDialog()
            }
        })
    }

    private fun loadTemplate() {
        val sourceType = intent.getStringExtra(EXTRA_TEMPLATE_SOURCE_TYPE) ?: "builtin"

        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                when (sourceType) {
                    "builtin" -> {
                        val generatorId = intent.getIntExtra(EXTRA_TEMPLATE_GENERATOR_ID, 0)
                        TemplateGenerator.generateTemplate(generatorId)
                    }
                    "asset" -> {
                        val assetPath = intent.getStringExtra(EXTRA_TEMPLATE_ASSET_PATH)
                        if (assetPath != null) {
                            try {
                                val stream = assets.open(assetPath)
                                val bm = BitmapFactory.decodeStream(stream)
                                stream.close()
                                bm
                            } catch (e: Exception) {
                                null
                            }
                        } else null
                    }
                    else -> null
                }
            }

            bitmap?.let {
                binding.drawingView.templateBitmap = it
            } ?: run {
                Toast.makeText(this@DrawingActivity, "加载模板失败", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun setupColorPalette() {
        val colorContainer = binding.layoutColors
        val normalSize = resources.getDimensionPixelSize(R.dimen.color_circle_size)
        val selectedSize = resources.getDimensionPixelSize(R.dimen.color_selected_size)
        val colorMargin = resources.getDimensionPixelSize(R.dimen.color_margin)
        // 选中态视觉高度 = selectedSize × 最大缩放 + 描边余量
        val containerHeight = (selectedSize * 1.08f).toInt() + 8  // 8px 上下留白
        colorContainer.layoutParams.height = containerHeight
        // 默认选中红色
        binding.drawingView.currentColor = ContextCompat.getColor(this, R.color.draw_red)

        for ((index, colorRes) in colorPalette.withIndex()) {
            val colorView = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(normalSize, normalSize).apply {
                    marginEnd = colorMargin
                }
                setImageResource(R.drawable.bg_color_circle)
                setColorFilter(ContextCompat.getColor(this@DrawingActivity, colorRes))
                setOnClickListener {
                    if (selectedColorView == it) return@setOnClickListener
                    val color = ContextCompat.getColor(this@DrawingActivity, colorRes)
                    binding.drawingView.currentColor = color

                    // ── 取消前一个选中：先缩回再恢复尺寸 ──
                    selectedColorView?.let { prev ->
                        prev.animate()
                            .scaleX(1f).scaleY(1f)
                            .setDuration(200)
                            .setInterpolator(null)
                            .withEndAction {
                                prev.setBackgroundResource(R.drawable.bg_color_circle)
                                prev.setPadding(0, 0, 0, 0)
                                val lp = prev.layoutParams
                                lp.width = normalSize
                                lp.height = normalSize
                                prev.layoutParams = lp
                            }
                            .start()
                    }

                    // ── 选中当前颜色：放大 + 白色光环 + 深色外框 ──
                    it.setBackgroundResource(R.drawable.bg_color_circle_selected)
                    it.setPadding(5, 5, 5, 5)

                    val lp = it.layoutParams
                    lp.width = selectedSize
                    lp.height = selectedSize
                    it.layoutParams = lp

                    it.animate()
                        .scaleX(1.08f).scaleY(1.08f)
                        .setDuration(300)
                        .setInterpolator(OvershootInterpolator(1.5f))
                        .withEndAction {
                            it.animate()
                                .scaleX(1.03f).scaleY(1.03f)
                                .setDuration(150)
                                .start()
                        }
                        .start()

                    selectedColorView = it
                }
            }
            colorContainer.addView(colorView)

            // 默认选中第一个颜色（红色）
            if (index == 0) {
                colorView.post { colorView.performClick() }
            }
        }
    }

    private fun setupToolButtons() {
        // 画笔
        binding.btnBrush.setBackgroundResource(R.drawable.bg_tool_button_selected)
        binding.btnBrush.setOnClickListener {
            binding.drawingView.currentTool = DrawingView.Tool.BRUSH
            updateToolSelection(it)
        }

        // 橡皮擦
        binding.btnEraser.setOnClickListener {
            binding.drawingView.currentTool = DrawingView.Tool.ERASER
            updateToolSelection(it)
        }

        // 填充
        binding.btnFill.setOnClickListener {
            binding.drawingView.currentTool = DrawingView.Tool.FILL
            updateToolSelection(it)
        }

        // 撤销
        binding.btnUndo.setOnClickListener {
            binding.drawingView.undo()
            updateUndoRedoState()
        }

        // 重做
        binding.btnRedo.setOnClickListener {
            binding.drawingView.redo()
            updateUndoRedoState()
        }

        // 清空
        binding.btnClear.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("清空画布")
                .setMessage("确定要清空所有绘画吗？")
                .setPositiveButton("确定") { _, _ ->
                    binding.drawingView.clearAll()
                    updateUndoRedoState()
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    private fun updateToolSelection(selectedView: View) {
        binding.btnBrush.setBackgroundResource(R.drawable.bg_tool_button)
        binding.btnEraser.setBackgroundResource(R.drawable.bg_tool_button)
        binding.btnFill.setBackgroundResource(R.drawable.bg_tool_button)
        selectedView.setBackgroundResource(R.drawable.bg_tool_button_selected)
    }

    private fun setupBrushSize() {
        binding.sbBrushSize.progress = 20
        binding.sbBrushSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                binding.drawingView.strokeWidth = progress.toFloat()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })
    }

    private fun setupActionButtons() {
        // 保存
        binding.btnSave.setOnClickListener {
            saveToGallery()
        }

        // 放弃
        binding.btnDiscard.setOnClickListener {
            showDiscardConfirmDialog()
        }
    }

    private fun showDiscardConfirmDialog() {
        AlertDialog.Builder(this)
            .setTitle("放弃绘画")
            .setMessage(getString(R.string.discard_confirm))
            .setPositiveButton("确定放弃") { _, _ ->
                finish()
                applyTransition(TRANSITION_CLOSE, android.R.anim.slide_in_left, android.R.anim.slide_out_right)
            }
            .setNegativeButton("继续画", null)
            .show()
    }

    /**
     * 区域锁定切换按钮（默认开启）。
     * 按钮样式：绿色药丸 = 锁定 / 橙色药丸 = 自由涂色。
     */
    private fun setupRegionLock() {
        val lockBtn = binding.btnRegionLock
        val drawingView = binding.drawingView

        fun updateLockUI() {
            if (drawingView.constrainToRegion) {
                lockBtn.text = getString(R.string.region_lock_on_text)
                lockBtn.setBackgroundResource(R.drawable.bg_region_lock_on)
            } else {
                lockBtn.text = getString(R.string.region_lock_off_text)
                lockBtn.setBackgroundResource(R.drawable.bg_region_lock_off)
            }
        }

        drawingView.onRegionReadyListener = { updateLockUI() }

        lockBtn.setOnClickListener {
            drawingView.constrainToRegion = !drawingView.constrainToRegion
            updateLockUI()
            Toast.makeText(this,
                if (drawingView.constrainToRegion) R.string.region_toggle_on else R.string.region_toggle_off,
                Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateUndoRedoState() {
        binding.btnUndo.alpha = if (binding.drawingView.canUndo()) 1.0f else 0.4f
        binding.btnRedo.alpha = if (binding.drawingView.canRedo()) 1.0f else 0.4f
    }

    private fun saveToGallery() {
        lifecycleScope.launch {
            val resultBitmap = withContext(Dispatchers.Default) {
                binding.drawingView.exportBitmap()
            }

            if (resultBitmap == null) {
                Toast.makeText(this@DrawingActivity, "保存失败", Toast.LENGTH_SHORT).show()
                return@launch
            }

            val success = withContext(Dispatchers.IO) {
                saveBitmapToGallery(resultBitmap)
            }

            if (success) {
                Toast.makeText(this@DrawingActivity, R.string.save_success, Toast.LENGTH_SHORT).show()
                finish()
            } else {
                Toast.makeText(this@DrawingActivity, R.string.save_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun saveBitmapToGallery(bitmap: Bitmap): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+ 使用 MediaStore
                val contentValues = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "Drawing_${System.currentTimeMillis()}.png")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/宝贝画画")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }

                val uri = contentResolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    contentValues
                ) ?: return false

                contentResolver.openOutputStream(uri)?.use { outputStream ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
                }

                contentValues.clear()
                contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                contentResolver.update(uri, contentValues, null, null)
                true
            } else {
                // Android 9 及以下
                val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                val appDir = File(picturesDir, "宝贝画画")
                if (!appDir.exists()) appDir.mkdirs()

                val file = File(appDir, "Drawing_${System.currentTimeMillis()}.png")
                FileOutputStream(file).use { outputStream ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
                }

                // 通知相册刷新
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DATA, file.absolutePath)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                }
                contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                true
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        binding.drawingView.release()
    }
}
