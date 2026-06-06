package com.zucky.drawing

import android.app.Activity
import android.os.Build

/**
 * 兼容 API 34+ 的 Activity 切换动画，替代已废弃的 [Activity.overridePendingTransition]。
 *
 * @param type  [Activity.OVERRIDE_TRANSITION_OPEN]（启动新页面）或
 *              [Activity.OVERRIDE_TRANSITION_CLOSE]（finish 返回）
 */
fun Activity.applyTransition(type: Int, enterAnim: Int, exitAnim: Int) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        overrideActivityTransition(type, enterAnim, exitAnim)
    } else {
        @Suppress("DEPRECATION")
        overridePendingTransition(enterAnim, exitAnim)
    }
}
