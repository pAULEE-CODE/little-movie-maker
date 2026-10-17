package com.animationworkshop.app

import org.junit.Assert.assertEquals
import org.junit.Test

class UiLocalizationTest {
    @Test
    fun pairedLabelsResolveInAllThreeModes() {
        assertEquals("Preview", resolveUiText("Preview / 預覽", "ENGLISH"))
        assertEquals("預覽", resolveUiText("Preview / 預覽", "TRADITIONAL_CHINESE"))
        assertEquals("Preview / 預覽", resolveUiText("Preview / 預覽", "BILINGUAL"))
    }

    @Test
    fun englishOnlyAndChineseOnlyLabelsResolveThroughSharedDictionary() {
        assertEquals("模式", resolveUiText("MODES", "TRADITIONAL_CHINESE"))
        assertEquals("MODES", resolveUiText("模式", "ENGLISH"))
        assertEquals("MODES / 模式", resolveUiText("模式", "BILINGUAL"))
    }

    @Test
    fun dynamicFrameAndSpeedLabelsRetainValuesInChinese() {
        assertEquals("第3格，共12格", resolveUiText("Frame 3 of 12", "TRADITIONAL_CHINESE"))
        assertEquals("速度 · 8 幀/秒", resolveUiText("Speed · 8 FPS", "TRADITIONAL_CHINESE"))
        assertEquals("Frame 3 of 12", resolveUiText("Frame 3 of 12", "ENGLISH"))
        assertEquals("完成65%", resolveUiText("65% complete", "TRADITIONAL_CHINESE"))
        assertEquals("5 frames / 5 格 · Oct 18, 2026", resolveUiText("5 frames / 張  ·  Oct 18, 2026", "BILINGUAL"))
    }
}
