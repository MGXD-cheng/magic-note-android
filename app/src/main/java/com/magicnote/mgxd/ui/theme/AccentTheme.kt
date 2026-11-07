package com.magicnote.mgxd.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

/**
 * 可选主题色。
 *
 * 严格按 Material 3 tonal palette 组织（每个色系给出 tone 10/20/30/40/80/90）：
 *  - Light：primary = tone40，onPrimary = white，primaryContainer = tone90，onPrimaryContainer = tone10
 *  - Dark ：primary = tone80，onPrimary = tone20，primaryContainer = tone30，onPrimaryContainer = tone90
 *
 * secondary / tertiary 由 primary 按色相旋转派生（M3 的调色板也是这么生成的），
 * 这样每个主题色都有完整、协调的 M3 角色色，而不是只换一个主色。
 * 背景/表面色沿用统一的品牌中性色（BackgroundLight/Dark），保证深浅两种模式都成立。
 */
enum class AccentTheme(
    val id: String,
    val label: String,
    val t10: Long,
    val t20: Long,
    val t30: Long,
    val t40: Long,
    val t80: Long,
    val t90: Long
) {
    BRAND("brand", "靛紫", 0xFF1E1256, 0xFF2A1F6E, 0xFF4F448D, 0xFF5B4DC7, 0xFFC6BDFF, 0xFFE8DEFF),
    BLUE("blue", "海蓝", 0xFF00173A, 0xFF002B5C, 0xFF00448A, 0xFF3F6FD8, 0xFFAECBFF, 0xFFD8E6FF),
    GREEN("green", "森绿", 0xFF00210F, 0xFF00391F, 0xFF00522F, 0xFF2E9E6B, 0xFF8FE9B4, 0xFFC8F5D8),
    TEAL("teal", "青碧", 0xFF00201C, 0xFF003731, 0xFF004F46, 0xFF1F8E86, 0xFF7FEFE2, 0xFFC0FFF5),
    ORANGE("orange", "蜜橙", 0xFF2D1600, 0xFF4A2800, 0xFF6A3C00, 0xFFC97A2B, 0xFFFFC58A, 0xFFFFE0C0),
    PINK("pink", "樱粉", 0xFF3E0024, 0xFF5E113C, 0xFF7B2955, 0xFFC1587C, 0xFFFFB0C8, 0xFFFFD9E2),

    /** 系统取色（Android 12+ 动态色）：不做色调覆盖，直接交给 dynamicColorScheme */
    DYNAMIC("dynamic", "系统取色", 0xFF1E1256, 0xFF2A1F6E, 0xFF4F448D, 0xFF5B4DC7, 0xFFC6BDFF, 0xFFE8DEFF);

    fun primary(dark: Boolean): Color = Color(if (dark) t80 else t40)

    fun onPrimary(dark: Boolean): Color = if (dark) Color(t20) else Color(0xFFFFFFFF)

    fun primaryContainer(dark: Boolean): Color = Color(if (dark) t30 else t90)

    fun onPrimaryContainer(dark: Boolean): Color = Color(if (dark) t90 else t10)

    /** 设置页色块：一律用 tone90 的浅色，柔和不刺眼（深浅模式都能看清） */
    fun swatch(): Color = Color(t90)

    /** 把选中色系套到基础色板上（DYNAMIC 原样返回）
     *
     * 除了 primary 角色色，还按 M3 的 neutral palette 生成整套 surface / background /
     * outline（neutral 是同一色相、极低饱和的一组色），所以背景、卡片、底部导航栏
     * 都会带上与所选主题色一致的色调，而不是固定的紫灰。
     */
    fun apply(base: ColorScheme, dark: Boolean): ColorScheme {
        if (this == DYNAMIC) return base
        val p = primary(dark)
        val h = hueOf(p)
        val secHue = hueOf(tint(p, 14f, 0.45f))
        val terHue = hueOf(tint(p, 60f, 0.60f))

        // M3 的 neutral 系列：保持色相、饱和度极低
        fun neutral(tone: Int, sat: Float = 0.07f) = hsv(h, sat, tone)

        val onSurface = if (dark) neutral(90, 0.10f) else neutral(10, 0.10f)
        val onSurfaceVar = if (dark) neutral(80, 0.10f) else neutral(30, 0.12f)

        return base.copy(
            // --- primary ---
            primary = p,
            onPrimary = onPrimary(dark),
            primaryContainer = primaryContainer(dark),
            onPrimaryContainer = onPrimaryContainer(dark),
            // --- secondary / tertiary（同色系派生，M3 的辅助色关系）---
            secondary = if (dark) hsv(secHue, 0.32f, 80) else hsv(secHue, 0.42f, 40),
            onSecondary = if (dark) hsv(secHue, 0.28f, 20) else Color(0xFFFFFFFF),
            secondaryContainer = if (dark) hsv(secHue, 0.24f, 30) else hsv(secHue, 0.16f, 90),
            onSecondaryContainer = if (dark) hsv(secHue, 0.30f, 90) else hsv(secHue, 0.40f, 10),
            tertiary = if (dark) hsv(terHue, 0.34f, 80) else hsv(terHue, 0.44f, 40),
            onTertiary = if (dark) hsv(terHue, 0.28f, 20) else Color(0xFFFFFFFF),
            tertiaryContainer = if (dark) hsv(terHue, 0.24f, 30) else hsv(terHue, 0.16f, 90),
            onTertiaryContainer = if (dark) hsv(terHue, 0.30f, 90) else hsv(terHue, 0.40f, 10),
            // --- 背景 / 表面（M3 surface 层级）---
            background = if (dark) neutral(6) else neutral(99),
            onBackground = onSurface,
            surface = if (dark) neutral(6) else neutral(99),
            onSurface = onSurface,
            surfaceVariant = if (dark) neutral(30, 0.10f) else neutral(90, 0.10f),
            onSurfaceVariant = onSurfaceVar,
            surfaceTint = p,
            surfaceBright = if (dark) neutral(24) else neutral(98),
            surfaceDim = if (dark) neutral(6) else neutral(87),
            surfaceContainerLowest = if (dark) neutral(4) else neutral(100),
            surfaceContainerLow = if (dark) neutral(10) else neutral(96),
            surfaceContainer = if (dark) neutral(12) else neutral(94),
            surfaceContainerHigh = if (dark) neutral(17) else neutral(92),
            surfaceContainerHighest = if (dark) neutral(22) else neutral(90),
            // --- 描边 ---
            outline = if (dark) neutral(60, 0.09f) else neutral(50, 0.09f),
            outlineVariant = if (dark) neutral(30, 0.09f) else neutral(80, 0.09f),
            // --- 反色（对照 M3 inverse 角色）---
            inverseSurface = if (dark) neutral(90) else neutral(20),
            inverseOnSurface = if (dark) neutral(20) else neutral(95),
            inversePrimary = if (dark) primary(false) else primary(true),
            scrim = Color(0xFF000000),
            // --- 错误色（M3 固定基线）---
            error = if (dark) Color(0xFFF2B8B5) else Color(0xFFB3261E),
            onError = if (dark) Color(0xFF601410) else Color(0xFFFFFFFF),
            errorContainer = if (dark) Color(0xFF8C1D18) else Color(0xFFF9DEDC),
            onErrorContainer = if (dark) Color(0xFFF9DEDC) else Color(0xFF410E0B)
        )
    }

    /** 取 HSV 色相 */
    private fun hueOf(color: Color): Float {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(color.toArgb(), hsv)
        return hsv[0]
    }

    /** M3 tone → 近似颜色（HSV 的 V 取 tone/100） */
    private fun hsv(hue: Float, sat: Float, tone: Int): Color {
        val v = (tone.coerceIn(0, 100) / 100f).coerceIn(0f, 1f)
        return Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, sat.coerceIn(0f, 1f), v)))
    }

    /** 按色相旋转 + 降饱和派生辅助色（模拟 M3 的 secondary/tertiary 关系） */
    private fun tint(base: Color, hueShiftDeg: Float, satScale: Float): Color {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(base.toArgb(), hsv)
        hsv[0] = (hsv[0] + hueShiftDeg + 360f) % 360f
        hsv[1] = (hsv[1] * satScale).coerceIn(0f, 1f)
        return Color(android.graphics.Color.HSVToColor(hsv))
    }

    companion object {
        fun fromId(id: String?): AccentTheme = entries.firstOrNull { it.id == id } ?: DYNAMIC
    }
}
