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

    /** 把选中色系套到基础色板上（DYNAMIC 原样返回） */
    fun apply(base: ColorScheme, dark: Boolean): ColorScheme {
        if (this == DYNAMIC) return base
        val p = primary(dark)
        return base.copy(
            primary = p,
            onPrimary = onPrimary(dark),
            primaryContainer = primaryContainer(dark),
            onPrimaryContainer = onPrimaryContainer(dark),
            secondary = tint(p, 14f, 0.45f),
            tertiary = tint(p, 60f, 0.55f)
        )
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
