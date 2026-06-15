package com.zucky.drawing.gallery

import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.zucky.drawing.databinding.ItemTemplateBinding
import com.zucky.drawing.model.Template
import com.zucky.drawing.model.TemplateGenerator
import com.zucky.drawing.model.TemplateSource

class TemplateAdapter(
    private val onItemClick: (Template) -> Unit
) : ListAdapter<Template, TemplateAdapter.ViewHolder>(TemplateDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemTemplateBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(
        private val binding: ItemTemplateBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(template: Template) {
            binding.tvTemplateName.text = if (template.nameResId != 0) {
                binding.root.context.getString(template.nameResId)
            } else {
                template.name
            }
            binding.tvTemplateEmoji.text = template.emoji

            // 生成缩略图
            when (val source = template.source) {
                is TemplateSource.BuiltIn -> {
                    val bitmap = TemplateGenerator.generatePreview(source.generatorId, 256)
                    binding.ivTemplatePreview.setImageBitmap(bitmap)
                }
                is TemplateSource.AssetImage -> {
                    try {
                        val stream = binding.root.context.assets.open(source.assetPath)
                        val bitmap = BitmapFactory.decodeStream(stream)
                        binding.ivTemplatePreview.setImageBitmap(bitmap)
                        stream.close()
                    } catch (e: Exception) {
                        binding.tvTemplateEmoji.text = "❓"
                    }
                }
            }

            binding.root.setOnClickListener {
                onItemClick(template)
            }
        }
    }

    private class TemplateDiffCallback : DiffUtil.ItemCallback<Template>() {
        override fun areItemsTheSame(oldItem: Template, newItem: Template): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: Template, newItem: Template): Boolean =
            oldItem == newItem
    }
}
