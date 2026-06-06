package com.zucky.drawing

import android.content.Intent
import android.app.Activity
import android.os.Bundle
import android.view.animation.OvershootInterpolator
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.zucky.drawing.databinding.ActivityMainBinding
import com.zucky.drawing.gallery.GalleryActivity

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        // 必须在 super.onCreate 和 setContentView 之前调用
        installSplashScreen()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupViews()
    }

    private fun setupViews() {
        // 标题动画
        binding.tvTitle.alpha = 0f
        binding.tvTitle.translationY = -50f
        binding.tvTitle.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(600)
            .setStartDelay(200)
            .setInterpolator(OvershootInterpolator(1.5f))
            .start()

        // 副标题渐显
        binding.tvSubtitle.alpha = 0f
        binding.tvSubtitle.animate()
            .alpha(1f)
            .setDuration(500)
            .setStartDelay(500)
            .start()

        // 按钮弹入
        binding.btnStartDraw.scaleX = 0f
        binding.btnStartDraw.scaleY = 0f
        binding.btnStartDraw.animate()
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(500)
            .setStartDelay(700)
            .setInterpolator(OvershootInterpolator(2f))
            .start()

        // 装饰元素渐显
        binding.decoStars.alpha = 0f
        binding.decoStars.animate()
            .alpha(1f)
            .setDuration(600)
            .setStartDelay(900)
            .start()

        // 点击开始绘画
        binding.btnStartDraw.setOnClickListener {
            startActivity(Intent(this, GalleryActivity::class.java))
            applyTransition(Activity.OVERRIDE_TRANSITION_OPEN, android.R.anim.slide_in_left, android.R.anim.slide_out_right)
        }
    }
}
