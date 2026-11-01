package com.voiceassistant.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration

/**
 * 是否宽屏（横屏平板 / 车机 / 1920×1200 这类）。
 *
 * 用最短边判断，避免折叠屏/分屏下出现"看着很宽其实很挤"的布局：
 * 600dp 是 Android 官方推荐的大屏分界。
 */
@Composable
fun isWideLayout(): Boolean {
    val config = LocalConfiguration.current
    val minSide = minOf(config.screenWidthDp, config.screenHeightDp)
    return minSide >= 600
}
