package com.zucky.drawing.gallery

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import com.zucky.drawing.R
import com.zucky.drawing.databinding.ActivityGalleryBinding
import com.zucky.drawing.drawing.DrawingActivity
import com.zucky.drawing.model.Template
import com.zucky.drawing.model.TemplateGenerator
import com.zucky.drawing.model.TemplateSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

class GalleryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGalleryBinding
    private lateinit var adapter: TemplateAdapter
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGalleryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        loadTemplates()

        binding.btnBack.setOnClickListener {
            finish()
            overridePendingTransition(android.R.anim.slide_in_left, android.R.anim.slide_out_right)
        }
    }

    private fun setupRecyclerView() {
        adapter = TemplateAdapter { template ->
            onTemplateSelected(template)
        }
        // 竖屏3列，横屏5列（大屏横屏更多列）
        val spanCount = if (resources.configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE) 5 else 3
        binding.rvTemplates.layoutManager = GridLayoutManager(this, spanCount)
        binding.rvTemplates.adapter = adapter
    }

    private fun loadTemplates() {
        scope.launch {
            val templates = withContext(Dispatchers.IO) {
                val list = mutableListOf<Template>()

                // 加载内置模板
                list.addAll(TemplateGenerator.builtInTemplates)

                // 尝试从 assets/templates 文件夹加载图片模板
                try {
                    val assetFiles = assets.list("templates")
                    if (assetFiles != null) {
                        for (fileName in assetFiles) {
                            if (fileName.endsWith(".png") || fileName.endsWith(".jpg") || fileName.endsWith(".jpeg") || fileName.endsWith(".webp")) {
                                val name = fileName.substringBeforeLast(".")
                                    .replace("_", " ")
                                    .replaceFirstChar { it.uppercase() }
                                list.add(
                                    Template(
                                        id = "asset_$fileName",
                                        name = name,
                                        emoji = "🖼️",
                                        source = TemplateSource.AssetImage("templates/$fileName")
                                    )
                                )
                            }
                        }
                    }
                } catch (e: IOException) {
                    // assets/templates 文件夹不存在或为空，只使用内置模板
                }

                list
            }

            if (templates.isEmpty()) {
                Toast.makeText(this@GalleryActivity, R.string.no_template, Toast.LENGTH_LONG).show()
            }
            adapter.submitList(templates)
        }
    }

    private fun onTemplateSelected(template: Template) {
        val intent = Intent(this, DrawingActivity::class.java).apply {
            putExtra(DrawingActivity.EXTRA_TEMPLATE_ID, template.id)
            putExtra(DrawingActivity.EXTRA_TEMPLATE_NAME, template.name)
            when (val source = template.source) {
                is TemplateSource.BuiltIn -> {
                    putExtra(DrawingActivity.EXTRA_TEMPLATE_SOURCE_TYPE, "builtin")
                    putExtra(DrawingActivity.EXTRA_TEMPLATE_GENERATOR_ID, source.generatorId)
                }
                is TemplateSource.AssetImage -> {
                    putExtra(DrawingActivity.EXTRA_TEMPLATE_SOURCE_TYPE, "asset")
                    putExtra(DrawingActivity.EXTRA_TEMPLATE_ASSET_PATH, source.assetPath)
                }
            }
        }
        startActivity(intent)
        overridePendingTransition(android.R.anim.slide_in_left, android.R.anim.slide_out_right)
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
