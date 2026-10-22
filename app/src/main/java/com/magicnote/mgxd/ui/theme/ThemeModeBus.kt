package com.magicnote.mgxd.ui.theme

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 进程内「主题模式」即时值。
 *
 * DataStore 的读写是异步的（IO 调度 + 文件落盘），直接 collect 会让「点一下深色」到
 * 「界面真的变深」之间隔几百毫秒；MainActivity 优先读这里，就能做到点下即切
 * （随后 DataStore 的值再覆盖上来，两者最终一致）。
 */
object ThemeModeBus {
    /** null = 本进程内还没手动切过（回落 DataStore 的值） */
    val value = MutableStateFlow<String?>(null)

    fun set(mode: String) {
        value.value = mode
    }
}
