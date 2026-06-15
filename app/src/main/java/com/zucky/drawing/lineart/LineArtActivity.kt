package com.zucky.drawing.lineart

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import com.zucky.drawing.R
import com.zucky.drawing.TRANSITION_OPEN
import com.zucky.drawing.TRANSITION_CLOSE
import com.zucky.drawing.applyTransition
import com.zucky.drawing.databinding.ActivityLineartBinding
import com.zucky.drawing.drawing.DrawingActivity
import com.zucky.drawing.photo.ImageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencv.android.OpenCVLoader
import java.io.File
import android.widget.AdapterView
import android.widget.ArrayAdapter
import java.io.FileOutputStream

/**
 * 照片转线稿处理界面。
 *
 * 流程：加载照片 → 预览 → 线稿转换 → 预览/调整 → 进入绘图界面
 */
class LineArtActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PHOTO_URI = "photo_uri"

        /** 引擎内部标识（与显示顺序对应） */
        private val ENGINE_IDS = listOf("opencv", "xdog", "pidinet")

        /** 旧版显示名称 → 新引擎ID 兼容映射 */
        private val LEGACY_NAME_TO_ID = mapOf(
            "OpenCV" to "opencv",
            "XDoG" to "xdog",
            "PiDiNet" to "pidinet"
        )
    }

    private lateinit var binding: ActivityLineartBinding

    /** 原始照片 URI */
    private var photoUri: Uri? = null

    /** 原始照片 Bitmap */
    private var originalBitmap: Bitmap? = null

    /** 转换后的线稿 Bitmap */
    private var lineArtBitmap: Bitmap? = null

    /** 线稿引擎 */
    private lateinit var engine: LineArtEngine

    /** 细节级别 0~1 */
    private var detailLevel = 0.5f

    /** 是否正在转换 */
    private var isConverting = false

    /** 当前选中的引擎内部标识 */
    private var currentEngineId: String = "opencv"

    /** 保存的线稿临时文件路径（传递给 DrawingActivity） */
    private var savedLineArtPath: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLineartBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 初始化引擎
        engine = createEngine(currentEngineId)

        setupButtons()
        setupEngineSelector()
        loadPhoto()

        // 恢复状态（配置变更后重建）
        savedInstanceState?.let { saved ->
            detailLevel = saved.getFloat("detail_level", 0.5f)

            // 兼容旧版保存的显示名称
            val savedEngine = saved.getString("current_engine", "opencv")
            currentEngineId = LEGACY_NAME_TO_ID[savedEngine] ?: savedEngine

            binding.sbDetail.progress = (detailLevel * 100).toInt()

            // 恢复 Spinner 选择
            val engineIndex = ENGINE_IDS.indexOf(currentEngineId)
            if (engineIndex >= 0) {
                binding.spinnerEngine.setSelection(engineIndex)
            }

            // 重建引擎（OpenCV 初始化在 createEngine 中完成）
            engine = createEngine(currentEngineId)

            savedLineArtPath = saved.getString("saved_lineart_path")
            // 如果之前有保存的线稿文件，尝试恢复
            val path = savedLineArtPath
            if (path != null && java.io.File(path).exists()) {
                val bitmap = android.graphics.BitmapFactory.decodeFile(path)
                if (bitmap != null) {
                    lineArtBitmap = bitmap
                    binding.ivLineart.setImageBitmap(bitmap)
                    binding.ivLineart.visibility = View.VISIBLE
                    binding.tvLineartLabel.visibility = View.VISIBLE
                    binding.layoutDetail.visibility = View.VISIBLE
                    binding.btnStartColoring.isEnabled = true
                    binding.layoutProgress.visibility = View.VISIBLE
                    binding.progressBar.progress = 100
                    binding.tvProgress.text = getString(com.zucky.drawing.R.string.lineart_complete)
                }
            }
        }
    }

    /**
     * 创建指定 ID 的线稿引擎。
     * OpenCV 初始化在首次调用时自动进行。
     */
    private fun createEngine(engineId: String): LineArtEngine {
        return when (engineId) {
            "xdog" -> {
                ensureOpenCvInitialized()
                XDoGLineArtEngine()
            }
            "pidinet" -> PiDiNetLineArtEngine(this)
            else -> {
                ensureOpenCvInitialized()
                OpenCVLineArtEngine()
            }
        }
    }

    /**
     * 确保 OpenCV 已初始化（用于 OpenCV 和 XDoG 引擎）。
     */
    private fun ensureOpenCvInitialized() {
        val success = OpenCVLoader.initLocal()
        if (!success) {
            Toast.makeText(this, "OpenCV 初始化失败，尝试重新加载...", Toast.LENGTH_SHORT).show()
            val retry = OpenCVLoader.initLocal()
            if (!retry) {
                throw IllegalStateException("OpenCV 初始化失败，无法使用线稿功能")
            }
        }
    }

    private fun setupButtons() {
        // 返回
        binding.btnBack.setOnClickListener {
            applyTransition(TRANSITION_CLOSE, android.R.anim.slide_in_left, android.R.anim.slide_out_right)
            finish()
        }

        // 转换
        binding.btnConvert.setOnClickListener {
            startConversion()
        }

        // 重新选择
        binding.btnReselect.setOnClickListener {
            applyTransition(TRANSITION_CLOSE, android.R.anim.slide_in_left, android.R.anim.slide_out_right)
            finish()
        }

        // 开始涂色：始终可用，用户可选择直接涂色（原图）或转线稿后涂色
        binding.btnStartColoring.isEnabled = true
        binding.btnStartColoring.setOnClickListener {
            navigateToDrawing()
        }

        // 细节级别滑块
        binding.sbDetail.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                detailLevel = progress / 100f
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                // 如果已经转换过，自动重新转换
                if (lineArtBitmap != null && !isConverting) {
                    startConversion()
                }
            }
        })
    }

    /**
     * 设置引擎选择器 Spinner。
     */
    private fun setupEngineSelector() {
        val engineLabels = resources.getStringArray(com.zucky.drawing.R.array.lineart_engines)
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            engineLabels
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerEngine.adapter = adapter

        binding.spinnerEngine.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: android.view.View?,
                position: Int,
                id: Long
            ) {
                val engineId = ENGINE_IDS[position]
                if (engineId == currentEngineId) return

                currentEngineId = engineId
                engine = createEngine(engineId)
                val label = engineLabels[position]
                Toast.makeText(
                    this@LineArtActivity,
                    getString(com.zucky.drawing.R.string.lineart_engine_toast, label),
                    Toast.LENGTH_SHORT
                ).show()

                // 不同引擎的默认细节级别
                when (engineId) {
                    // 精细(XDoG) 默认较低级别，避免过度碎片化
                    "xdog" -> {
                        detailLevel = 0.125f
                        binding.sbDetail.progress = (detailLevel * 100).toInt()
                    }
                    // 专家(PiDiNet) 默认中等级别，深度学习模型对阈值敏感
                    "pidinet" -> {
                        detailLevel = 0.3f
                        binding.sbDetail.progress = (detailLevel * 100).toInt()
                    }
                }

                // 如果已经转换过，自动重新转换
                if (lineArtBitmap != null && !isConverting && originalBitmap != null) {
                    startConversion()
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    /**
     * 从 URI 加载照片。
     */
    private fun loadPhoto() {
        val uri = IntentCompat.getParcelableExtra(intent, EXTRA_PHOTO_URI, Uri::class.java)
        if (uri == null) {
            Toast.makeText(this, "无法加载照片", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        photoUri = uri

        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                // 降采样到合理大小（最长边 1024px）
                ImageLoader.decodeBitmapFromUri(
                    contentResolver, uri, 1024, 1024
                )
            }

            if (bitmap == null) {
                Toast.makeText(this@LineArtActivity, "加载照片失败", Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }

            originalBitmap = bitmap
            binding.ivOriginal.setImageBitmap(bitmap)
        }
    }

    /**
     * 启动线稿转换。
     */
    private fun startConversion() {
        val input = originalBitmap ?: return
        if (isConverting) return

        isConverting = true

        // 显示进度
        binding.layoutProgress.visibility = View.VISIBLE
        binding.progressBar.progress = 0
        binding.tvProgress.text = getString(com.zucky.drawing.R.string.lineart_converting)
        binding.btnConvert.isEnabled = false

        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.Default) {
                    engine.convert(input, detailLevel) { progress ->
                        // 在主线程更新进度（防御 Activity 已销毁的情况）
                        runOnUiThread {
                            if (!isFinishing && !isDestroyed) {
                                binding.progressBar.progress = (progress * 100).toInt()
                            }
                        }
                    }
                }

                // 回收旧线稿
                lineArtBitmap?.recycle()
                lineArtBitmap = result

                // 显示线稿预览
                binding.ivLineart.setImageBitmap(result)
                binding.ivLineart.visibility = View.VISIBLE
                binding.tvLineartLabel.visibility = View.VISIBLE
                binding.layoutDetail.visibility = View.VISIBLE

                // 保存线稿到临时文件
                savedLineArtPath = saveLineArtToTempFile(result)

                binding.tvProgress.text = getString(com.zucky.drawing.R.string.lineart_complete)

            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(
                    this@LineArtActivity,
                    "转换失败: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                isConverting = false
                binding.btnConvert.isEnabled = true
            }
        }
    }

    /**
     * 将线稿 Bitmap 保存为临时 PNG 文件。
     * DrawingActivity 将通过文件路径加载。
     */
    private fun saveLineArtToTempFile(bitmap: Bitmap): String? {
        return try {
            val file = File(cacheDir, "lineart_${System.currentTimeMillis()}.png")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            file.absolutePath
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * 进入绘图界面。
     * 如果已经转换过线稿，使用线稿；否则直接使用原图。
     */
    private fun navigateToDrawing() {
        val intent = Intent(this, DrawingActivity::class.java)

        val path = savedLineArtPath
        if (path != null) {
            // 已转换线稿，使用线稿
            intent.putExtra(DrawingActivity.EXTRA_TEMPLATE_SOURCE_TYPE, "lineart_file")
            intent.putExtra(DrawingActivity.EXTRA_TEMPLATE_FILE_PATH, path)
            intent.putExtra(DrawingActivity.EXTRA_TEMPLATE_NAME, getString(R.string.template_my_lineart))
        } else {
            // 未转换线稿，直接使用原图
            val bitmap = originalBitmap
            if (bitmap == null) {
                Toast.makeText(this, R.string.toast_photo_not_loaded, Toast.LENGTH_SHORT).show()
                return
            }
            // 将原图保存为临时文件
            val originalPath = saveBitmapToTempFile(bitmap, "original")
            if (originalPath == null) {
                Toast.makeText(this, R.string.toast_save_photo_failed, Toast.LENGTH_SHORT).show()
                return
            }
            intent.putExtra(DrawingActivity.EXTRA_TEMPLATE_SOURCE_TYPE, "lineart_file")
            intent.putExtra(DrawingActivity.EXTRA_TEMPLATE_FILE_PATH, originalPath)
            intent.putExtra(DrawingActivity.EXTRA_TEMPLATE_NAME, getString(R.string.template_my_photo))
            // 未转线稿的原图，默认关闭区域锁定
            intent.putExtra(DrawingActivity.EXTRA_DISABLE_REGION_LOCK, true)
        }

        applyTransition(TRANSITION_OPEN, android.R.anim.slide_in_left, android.R.anim.slide_out_right)
        startActivity(intent)
    }

    /**
     * 将 Bitmap 保存为临时 PNG 文件。
     */
    private fun saveBitmapToTempFile(bitmap: Bitmap, prefix: String): String? {
        return try {
            val file = File(cacheDir, "${prefix}_${System.currentTimeMillis()}.png")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            file.absolutePath
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putFloat("detail_level", detailLevel)
        outState.putString("current_engine", currentEngineId)
        outState.putString("saved_lineart_path", savedLineArtPath)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            originalBitmap?.recycle()
            originalBitmap = null
            lineArtBitmap?.recycle()
            lineArtBitmap = null
        }
    }
}
