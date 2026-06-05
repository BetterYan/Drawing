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
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
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
    }

    private fun loadTemplate() {
        val sourceType = intent.getStringExtra(EXTRA_TEMPLATE_SOURCE_TYPE) ?: "builtin"
        val templateName = intent.getStringExtra(EXTRA_TEMPLATE_NAME) ?: "画画"

        binding.tvTemplateName.text = templateName

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
        // 默认选中红色
        binding.drawingView.currentColor = ContextCompat.getColor(this, R.color.draw_red)

        for (colorRes in colorPalette) {
            val colorView = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    resources.getDimensionPixelSize(R.dimen.color_circle_size),
                    resources.getDimensionPixelSize(R.dimen.color_circle_size)
                ).apply {
                    marginEnd = resources.getDimensionPixelSize(R.dimen.color_margin)
                }
                setImageResource(R.drawable.bg_color_circle)
                setColorFilter(ContextCompat.getColor(this@DrawingActivity, colorRes))
                setOnClickListener {
                    val color = ContextCompat.getColor(this@DrawingActivity, colorRes)
                    binding.drawingView.currentColor = color
                    // 高亮选中
                    selectedColorView?.let {
                        it.setBackgroundResource(0)
                        it.setPadding(0, 0, 0, 0)
                    }
                    it.setBackgroundResource(R.drawable.bg_color_circle_selected)
                    it.setPadding(4, 4, 4, 4)
                    selectedColorView = it
                }
            }
            colorContainer.addView(colorView)
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
            AlertDialog.Builder(this)
                .setTitle("放弃绘画")
                .setMessage(getString(R.string.discard_confirm))
                .setPositiveButton("确定放弃") { _, _ ->
                    finish()
                    overridePendingTransition(android.R.anim.slide_in_left, android.R.anim.slide_out_right)
                }
                .setNegativeButton("继续画", null)
                .show()
        }
        binding.btnBackDrawing.setOnClickListener {
            finish()
            overridePendingTransition(android.R.anim.slide_in_left, android.R.anim.slide_out_right)
        }
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
