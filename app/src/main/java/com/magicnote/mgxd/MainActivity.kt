package com.magicnote.mgxd

import com.magicnote.mgxd.util.MgxdIntentHolder
import android.widget.Toast
import android.content.Intent
import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.magicnote.mgxd.notify.NotificationHelper
import com.magicnote.mgxd.notify.ReminderScheduler
import com.magicnote.mgxd.ui.navigation.AppNav
import com.magicnote.mgxd.ui.theme.MGTheme
import com.magicnote.mgxd.ui.theme.ThemeModeBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
class MainActivity : ComponentActivity() {
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 桌面小组件点击时带过来的目标 tab（0 今日 / 1 待办 / 2 日历 / 3 日记 / 4 AI / 5 设置） */
    private val pendingTab = mutableStateOf<Int?>(null)
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* 用户选择后无需额外处理 */ }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        NotificationHelper.ensureChannels(this)
        requestNotificationPermissionIfNeeded()
        // 恢复闹钟调度 + 按纯净模式决定是否启动后台守护（见 scheduleReminders）
        scheduleReminders()
        // 从文件管理器 / 浏览器打开 .mgxd 备份文件时直接进入导入流程
        handleViewIntent(intent)
        // 小组件点击：直接落到对应 tab
        pendingTab.value = intent?.getIntExtra(EXTRA_TAB, -1)?.takeIf { it >= 0 }
        // 打开 App 时刷新桌面小组件（数据可能在别处发生变化）
        com.magicnote.mgxd.widget.TodoWidgetProvider.refreshAll(this)
        com.magicnote.mgxd.widget.CountdownWidgetProvider.refreshAll(this)
        com.magicnote.mgxd.widget.QuickNoteWidgetProvider.refreshAll(this)

        setContent {
            // 外观主题：设置里的 跟随系统/浅色/深色
            val repo = (application as MGApp).container.repository
            val storedMode by repo.themeMode.collectAsState(initial = "system")
            // 进程内即时值优先：点下主题就切，不必等 DataStore 的异步回调
            val instantMode by ThemeModeBus.value.collectAsState()
            val themeMode = instantMode ?: storedMode
            val darkTheme = when (themeMode) {
                "dark" -> true
                "light" -> false
                else -> isSystemInDarkTheme()
            }
            MGTheme(darkTheme = darkTheme) {
                // 主题切换时，系统栏图标 / 窗口底色必须和内容「同一帧」更新。
                // 原来用 DisposableEffect（组合提交后才执行）→ 顶部状态栏会慢半拍，
                // 这里改成 remember(key)：在组合阶段就同步到窗口，和主题颜色一起生效。
                val view = LocalView.current
                val context = LocalContext.current
                val activity = context as? ComponentActivity
                val windowBgArgb = MaterialTheme.colorScheme.surface.toArgb()
                remember(darkTheme, windowBgArgb) {
                    if (activity != null) applyWindowTheme(activity, view, darkTheme, windowBgArgb)
                }
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppNav(initialTab = pendingTab.value)
                }
            }
        }
    }
    /**
     * 把系统栏外观 + 窗口底色按当前主题同步一次。
     *
     * ⚠️ 必须在「组合阶段」调用（remember {}），放到 DisposableEffect / LaunchedEffect 里
     * 会晚一帧执行，用户能看到状态栏（顶部）比界面内容慢一拍才变色。
     */
    private fun applyWindowTheme(
        activity: ComponentActivity,
        view: View,
        dark: Boolean,
        windowBgArgb: Int
    ) {
        val window = activity.window
        // 1) 系统栏样式跟随「App 内主题」，而不是系统 uiMode（App 选深色 + 系统浅色时才不会错乱）
        runCatching {
            activity.enableEdgeToEdge(
                statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { _ -> dark },
                navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { _ -> dark }
            )
        }
        // 2) 状态栏 / 导航栏图标深浅（深色主题用浅色图标，反之亦然）
        runCatching {
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
        // 3) 系统栏底色：华为/EMUI 等 ROM 在「透明状态栏」下有自己的一层绘制缓存，
        //    只改图标深浅不够，显式设色才会随主题立刻重绘（Android 15 起强制 edge-to-edge，此属性失效）
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT < 35) {
            runCatching {
                window.statusBarColor = windowBgArgb
                window.navigationBarColor = windowBgArgb
            }
        }
        // 4) 窗口底色：边到边时系统栏背后、以及主题切换过渡瞬间露出的颜色
        runCatching { window.decorView.setBackgroundColor(windowBgArgb) }
    }

    /** .mgxd 文件关联：系统以 VIEW 打开 .mgxd 文件时读取内容并投递给 UI */
    private fun handleViewIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        activityScope.launch {
            val text = runCatching {
                contentResolver.openInputStream(uri)?.use { ins ->
                    val bytes = ins.readBytes()
                    if (bytes.size > 32 * 1024 * 1024) null else String(bytes, Charsets.UTF_8)
                }
            }.getOrNull()
            if (text.isNullOrBlank()) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "无法读取该文件，可能不是 .mgxd 备份", Toast.LENGTH_LONG).show()
                }
            } else {
                MgxdIntentHolder.post(text)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleViewIntent(intent)
        // App 已在后台时点小组件：同样切到目标 tab
        pendingTab.value = intent.getIntExtra(EXTRA_TAB, -1).takeIf { it >= 0 }
    }

    companion object {
        /** 小组件 / 通知里用来指定打开哪个 tab */
        const val EXTRA_TAB = "extra_open_tab"
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun scheduleReminders() {
        activityScope.launch {
            val app = applicationContext as MGApp
            val repo = app.container.repository
            ReminderScheduler.rescheduleAll(applicationContext, repo)
        }
    }

    /** 保活服务是否已尝试启动（只启动一次，后续 onResume 不再重复） */
    private var keepAliveStarted = false

    override fun onResume() {
        super.onResume()
        ensureKeepAliveStarted()
    }

    /**
     * 启动后台守护（保活前台服务）。
     *
     * 时机说明：Android 12+ 限制「后台启动前台服务」，Android 14/15 校验更严；
     * 若在 onCreate 阶段就启动，系统可能仍判定为后台状态并抛
     * ForegroundServiceStartNotAllowedException（**首次安装后打开最易触发**），
     * 异常发生在主线程会把整个进程带崩 → 表现为「一点开就闪退」。
     * 因此改为界面可见（onResume）后再启动，且全程兜底，失败只降级不崩溃。
     */
    private fun ensureKeepAliveStarted() {
        if (keepAliveStarted) return
        keepAliveStarted = true
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val repo = (application as MGApp).container.repository
                    // 纯净模式：用户主动关闭了所有后台能力，不启动守护
                    if (!repo.pureMode.first()) {
                        com.magicnote.mgxd.service.KeepAliveService.start(applicationContext)
                    }
                }
            }.onFailure {
                android.util.Log.w("MainActivity", "keep-alive start skipped: ${it.message}")
            }
        }
    }
}