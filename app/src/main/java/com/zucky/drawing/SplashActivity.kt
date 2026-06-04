package com.zucky.drawing

import android.animation.ObjectAnimator
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.view.animation.ScaleAnimation
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.zucky.drawing.databinding.ActivitySplashBinding

class SplashActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySplashBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 全屏沉浸
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        // 启动动画
        playSplashAnimation()

        // 2秒后跳转主界面
        Handler(Looper.getMainLooper()).postDelayed({
            startActivity(Intent(this, MainActivity::class.java))
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            finish()
        }, 2500)
    }

    private fun playSplashAnimation() {
        // Logo 从透明渐显 + 弹性缩放
        binding.ivLogo.alpha = 0f
        binding.ivLogo.scaleX = 0.3f
        binding.ivLogo.scaleY = 0.3f

        binding.ivLogo.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(800)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()

        // 文字延迟出现
        binding.tvMessage.alpha = 0f
        binding.tvMessage.animate()
            .alpha(1f)
            .setDuration(600)
            .setStartDelay(400)
            .start()

        // Loading 点动画
        binding.loadingDots.alpha = 0f
        binding.loadingDots.animate()
            .alpha(1f)
            .setDuration(400)
            .setStartDelay(800)
            .start()
    }
}
