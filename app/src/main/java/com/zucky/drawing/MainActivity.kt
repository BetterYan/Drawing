package com.zucky.drawing

import android.content.Intent
import android.os.Bundle
import android.view.ViewTreeObserver
import android.view.animation.OvershootInterpolator
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.zucky.drawing.databinding.ActivityMainBinding
import com.zucky.drawing.gallery.GalleryActivity

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    /** 是否首次创建（非从回退栈恢复），用于跳过返回时的入场动画 */
    private var isFirstLaunch = true

    override fun onCreate(savedInstanceState: Bundle?) {
        // ── 使用 setKeepOnScreenCondition 防止 Splash → Activity 之间的闪白屏 ──
        val splashScreen = installSplashScreen()
        var splashDismissed = false
        splashScreen.setKeepOnScreenCondition { !splashDismissed }

        isFirstLaunch = savedInstanceState == null
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 将视图设置为动画前的初始状态
        initViewStates()

        // 等待布局首次完成测量和绘制后，关闭 Splash 并启动动画
        binding.root.viewTreeObserver.addOnPreDrawListener(
            object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    binding.root.viewTreeObserver.removeOnPreDrawListener(this)
                    splashDismissed = true // 允许 Splash 关闭
                    if (isFirstLaunch) {
                        startEntranceAnimations()
                    }
                    return true
                }
            }
        )

        // 点击开始绘画 — 转场动画必须在 startActivity 之前设置
        binding.btnStartDraw.setOnClickListener {
            applyTransition(TRANSITION_OPEN, android.R.anim.slide_in_left, android.R.anim.slide_out_right)
            startActivity(Intent(this, GalleryActivity::class.java))
        }
    }

    /** 所有动画元素恢复到初始隐藏状态 */
    private fun initViewStates() {
        binding.tvTitle.alpha = 0f
        binding.tvTitle.translationY = -50f

        binding.tvSubtitle.alpha = 0f

        binding.btnStartDraw.scaleX = 0f
        binding.btnStartDraw.scaleY = 0f

        binding.decoStars.alpha = 0f
    }

    /** 入场动画序列（仅在首次启动时播放） */
    private fun startEntranceAnimations() {
        // 标题从上方弹入
        binding.tvTitle.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(600)
            .setStartDelay(100)
            .setInterpolator(OvershootInterpolator(1.5f))
            .start()

        // 副标题渐显
        binding.tvSubtitle.animate()
            .alpha(1f)
            .setDuration(500)
            .setStartDelay(400)
            .start()

        // 按钮弹入
        binding.btnStartDraw.animate()
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(500)
            .setStartDelay(600)
            .setInterpolator(OvershootInterpolator(2f))
            .start()

        // 装饰星星渐显
        binding.decoStars.animate()
            .alpha(1f)
            .setDuration(600)
            .setStartDelay(800)
            .start()
    }
}
