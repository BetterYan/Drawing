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
import com.zucky.drawing.TRANSITION_OPEN
import com.zucky.drawing.TRANSITION_CLOSE
import com.zucky.drawing.applyTransition
import com.zucky.drawing.databinding.ActivityLineartBinding
import com.zucky.drawing.drawing.DrawingActivity
import com.zucky.drawing.photo.ImageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * 照片转线稿处理界面。
 *
 * 流程：加载照片 → 预览 → 线稿转换 → 预览/调整 → 进入绘图界面
 */
class LineArtActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PHOTO_URI = "photo_uri"
    }

    private lateinit var binding: ActivityLineartBinding

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

    /** 保存的线稿临时文件路径（传递给 DrawingActivity） */
    private var savedLineArtPath: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLineartBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 初始化引擎（优先 ML，不可用则 XDoG）
        engine = createBestAvailableEngine()

        setupButtons()
        loadPhoto()

        // 恢复状态（配置变更后重建）
        savedInstanceState?.let { saved ->
            detailLevel = saved.getFloat("detail_level", 0.5f)
            binding.sbDetail.progress = (detailLevel * 100).toInt()
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
     * 创建最佳可用引擎。
     * 优先尝试 ML 引擎（ControlNet），不可用则回退到 XDoG。
     */
    private fun createBestAvailableEngine(): LineArtEngine {
        val mlEngine = MlLineArtEngine(this)
        if (mlEngine.isAvailable()) {
            Toast.makeText(this, "使用 ML 引擎 (ControlNet)", Toast.LENGTH_SHORT).show()
            return mlEngine
        }
        // 回退到 XDoG
        Toast.makeText(this, "使用 XDoG 经典引擎", Toast.LENGTH_SHORT).show()
        return XDoGLineArtEngine()
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

        // 开始涂色
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
     * 从 URI 加载照片。
     */
    private fun loadPhoto() {
        val uri = IntentCompat.getParcelableExtra(intent, EXTRA_PHOTO_URI, Uri::class.java)
        if (uri == null) {
            Toast.makeText(this, "无法加载照片", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

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
        binding.btnStartColoring.isEnabled = false

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
                binding.btnStartColoring.isEnabled = true

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
     */
    private fun navigateToDrawing() {
        val path = savedLineArtPath
        if (path == null) {
            Toast.makeText(this, "线稿未保存，请重新转换", Toast.LENGTH_SHORT).show()
            return
        }

        val intent = Intent(this, DrawingActivity::class.java).apply {
            putExtra(DrawingActivity.EXTRA_TEMPLATE_SOURCE_TYPE, "lineart_file")
            putExtra(DrawingActivity.EXTRA_TEMPLATE_FILE_PATH, path)
            putExtra(DrawingActivity.EXTRA_TEMPLATE_NAME, "我的线稿")
        }
        applyTransition(TRANSITION_OPEN, android.R.anim.slide_in_left, android.R.anim.slide_out_right)
        startActivity(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putFloat("detail_level", detailLevel)
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
