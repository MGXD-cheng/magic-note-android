package com.magicnote.mgxd.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.RemoteViews
import com.magicnote.mgxd.MGApp
import com.magicnote.mgxd.MainActivity
import com.magicnote.mgxd.R
import com.magicnote.mgxd.data.repo.AppRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private val widgetScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

private fun repoOf(context: Context): AppRepository =
    (context.applicationContext as MGApp).container.repository

private fun pendingFlags(): Int =
    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

/**
 * 打开 App 并直接落到指定 tab（0 今日 / 1 待办 / 2 日历 / 3 日记 / 4 Magic AI / 5 设置）
 * 不同 tab 用不同 requestCode，避免 PendingIntent 互相覆盖。
 */
private fun openAppPendingIntent(context: Context, tab: Int = 0): PendingIntent {
    val intent = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        putExtra(MainActivity.EXTRA_TAB, tab)
    }
    return PendingIntent.getActivity(context, 1000 + tab, intent, pendingFlags())
}

// ============================================================
// 今日待办组件：展示今日未完成待办，支持在桌面直接点勾完成
// ============================================================
class TodoWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { render(context, mgr, it) }
    }

    /** 用户拉伸/缩小小组件时立即重排（否则要等 30 分钟的周期刷新） */
    override fun onAppWidgetOptionsChanged(
        context: Context,
        mgr: AppWidgetManager,
        widgetId: Int,
        newOptions: android.os.Bundle
    ) {
        render(context, mgr, widgetId)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_TOGGLE) {
            val id = intent.getLongExtra(EXTRA_ID, 0L)
            if (id > 0) {
                val pending = goAsync()
                widgetScope.launch {
                    try {
                        val repo = repoOf(context)
                        val todo = repo.observeTodos().first().firstOrNull { it.id == id }
                        if (todo != null) {
                            repo.updateTodo(todo.copy(completed = !todo.completed))
                        }
                    } catch (_: Exception) {
                    } finally {
                        pending.finish()
                    }
                    refreshAll(context)
                }
            }
        }
    }

    companion object {
        const val ACTION_TOGGLE = "com.magicnote.mgxd.widget.ACTION_TOGGLE_TODO"
        const val EXTRA_ID = "extra_todo_id"

        fun refreshAll(context: Context) {
            val cls = TodoWidgetProvider::class.java
            val mgr = AppWidgetManager.getInstance(context)
            mgr.getAppWidgetIds(ComponentName(context, cls)).forEach { render(context, mgr, it) }
        }

        private fun render(context: Context, mgr: AppWidgetManager, widgetId: Int) {
            widgetScope.launch {
                val views = RemoteViews(context.packageName, R.layout.widget_todo)
                try {
                    val repo = repoOf(context)
                    val pending = repo.observeTodos().first()
                        .filter { !it.isLongTerm && !it.completed }
                        .sortedWith(compareBy({ it.dueTime ?: Long.MAX_VALUE }, { -it.priority }))

                    val openTodoTab = openAppPendingIntent(context, 1)   // 直接进待办页
                    val openHomeTab = openAppPendingIntent(context, 0)

                    views.setTextViewText(
                        R.id.widget_todo_count,
                        if (pending.isEmpty()) "" else pending.size.toString() + " 项待办"
                    )

                    // ===== 按小组件实际尺寸决定显示几条（空间利用率） =====
                    // 固定开销 ≈ padding(10*2) + 标题行(18) + 间距(4+3) + 底部"查看全部"(14) ≈ 52dp
                    val optH = mgr.getAppWidgetOptions(widgetId)
                        .getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 110)
                    val rows = ((optH - 52) / 24).coerceIn(1, 7)   // 每行至少 24dp
                    val shown = pending.take(rows)

                    views.removeAllViews(R.id.widget_todo_list)
                    val hasItems = shown.isNotEmpty()
                    views.setViewVisibility(
                        R.id.widget_todo_list,
                        if (hasItems) View.VISIBLE else View.GONE
                    )
                    views.setViewVisibility(
                        R.id.widget_todo_empty,
                        if (hasItems) View.GONE else View.VISIBLE
                    )
                    views.setTextViewText(R.id.widget_todo_empty, "今天没有待办，点这里加一条 ✨")
                    views.setOnClickPendingIntent(R.id.widget_todo_empty, openTodoTab)

                    shown.forEach { todo ->
                        val item = RemoteViews(context.packageName, R.layout.widget_todo_item)
                        item.setTextViewText(R.id.widget_item_text, todo.title)
                        // 圆圈 → 直接完成；标题/整行 → 进待办页（避免想看详情时误触完成）
                        item.setOnClickPendingIntent(R.id.widget_item_check, toggleIntent(context, todo.id))
                        item.setOnClickPendingIntent(R.id.widget_item_text, openTodoTab)
                        item.setOnClickPendingIntent(R.id.widget_item_row, openTodoTab)
                        views.addView(R.id.widget_todo_list, item)
                    }

                    views.setOnClickPendingIntent(R.id.widget_todo_title, openTodoTab)
                    views.setOnClickPendingIntent(R.id.widget_todo_footer, openHomeTab)
                } catch (_: Exception) {
                }
                mgr.updateAppWidget(widgetId, views)
            }
        }

        private fun toggleIntent(context: Context, id: Long): PendingIntent {
            val intent = Intent(context, TodoWidgetProvider::class.java).apply {
                action = ACTION_TOGGLE
                data = Uri.parse("magicnote://todo/$id")
                putExtra(EXTRA_ID, id)
            }
            return PendingIntent.getBroadcast(context, id.toInt(), intent, pendingFlags())
        }
    }
}

// ============================================================
// 倒数日组件：醒目展示最近的两个倒数日
// ============================================================
class CountdownWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { render(context, mgr, it) }
    }

    companion object {
        fun refreshAll(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            mgr.getAppWidgetIds(ComponentName(context, CountdownWidgetProvider::class.java))
                .forEach { render(context, mgr, it) }
        }

        private fun render(context: Context, mgr: AppWidgetManager, widgetId: Int) {
            widgetScope.launch {
                val views = RemoteViews(context.packageName, R.layout.widget_countdown)
                try {
                    val repo = repoOf(context)
                    val list = repo.observeCountdowns().first()
                        .sortedBy { it.daysLeft }
                        .take(2)

                    views.setViewVisibility(
                        R.id.widget_cd_empty,
                        if (list.isEmpty()) View.VISIBLE else View.GONE
                    )
                    val rows = listOf(
                        Triple(R.id.widget_cd_row1, R.id.widget_cd_name1, R.id.widget_cd_days1),
                        Triple(R.id.widget_cd_row2, R.id.widget_cd_name2, R.id.widget_cd_days2)
                    )
                    rows.forEachIndexed { index, (rowId, nameId, daysId) ->
                        val item = list.getOrNull(index)
                        if (item == null) {
                            views.setViewVisibility(rowId, View.GONE)
                        } else {
                            views.setViewVisibility(rowId, View.VISIBLE)
                            views.setTextViewText(nameId, item.title)
                            views.setTextViewText(daysId, daysText(item.daysLeft))
                            // 紧急度配色：已过/今天 → 红，7 天内 → 橙，更远 → 紫
                            val dayColor = when {
                                item.daysLeft <= 0L -> 0xFFFF5252.toInt()
                                item.daysLeft <= 7L -> 0xFFFFA726.toInt()
                                else -> 0xFF9C6BFF.toInt()
                            }
                            views.setTextColor(daysId, dayColor)
                        }
                    }
                    // 倒数日在「待办」页，直接跳过去
                    val openCountdown = openAppPendingIntent(context, 1)
                    views.setOnClickPendingIntent(R.id.widget_cd_title, openCountdown)
                    views.setOnClickPendingIntent(R.id.widget_cd_root, openCountdown)
                } catch (_: Exception) {
                }
                mgr.updateAppWidget(widgetId, views)
            }
        }

        private fun daysText(days: Long): String = when {
            days > 0 -> "还有 " + days + " 天"
            days == 0L -> "就是今天 🎉"
            else -> "已过 " + (-days) + " 天"
        }
    }
}

// ============================================================
// 一句话记录组件：点击弹出输入框，AI 解析后自动分类存入待办 / 日记
// ============================================================
class QuickNoteWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { render(context, mgr, it) }
    }

    companion object {
        fun refreshAll(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            mgr.getAppWidgetIds(ComponentName(context, QuickNoteWidgetProvider::class.java))
                .forEach { render(context, mgr, it) }
        }

        private fun render(context: Context, mgr: AppWidgetManager, widgetId: Int) {
            val views = RemoteViews(context.packageName, R.layout.widget_quicknote)
            val intent = Intent(context, QuickNoteActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            views.setOnClickPendingIntent(
                R.id.widget_quicknote_root,
                PendingIntent.getActivity(context, 0, intent, pendingFlags())
            )
            mgr.updateAppWidget(widgetId, views)
        }
    }
}