package com.magicnote.mgxd.util

import com.magicnote.mgxd.data.db.CalendarEventEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 循环日程展开器
 *
 * 设计：循环日程**只有一条** Entity（带规则），渲染时按查询窗口展开成实例（Occurrence）。
 * 好处：不产生脏数据、编辑/删除天然是“整组”操作、旧数据（recurrence=NONE）零迁移成本。
 */
object RecurrenceHelper {

    const val NONE = "NONE"
    const val DAILY = "DAILY"
    const val WEEKLY = "WEEKLY"
    const val MONTHLY = "MONTHLY"

    /** 展开后的一个实例（start/end 为该次发生的实际时间） */
    data class Occurrence(
        val event: CalendarEventEntity,
        val start: Long,
        val end: Long
    ) {
        val isRecurring: Boolean get() = event.recurrence.uppercase() != NONE && event.recurrence.isNotBlank()
        /** 唯一 key（同一条循环日程的不同实例） */
        val key: String get() = "${event.id}_$start"
    }

    /** 规则的人类可读描述（用于卡片上的小标签） */
    fun label(event: CalendarEventEntity): String = when (event.recurrence.uppercase()) {
        DAILY -> "每天"
        WEEKLY -> {
            val wd = if (event.recurrenceWeekday in 1..7) event.recurrenceWeekday
            else Instant.ofEpochMilli(event.startTime).atZone(ZoneId.systemDefault()).dayOfWeek.value
            "每周" + weekdayCn(wd)
        }
        MONTHLY -> "每月" + Instant.ofEpochMilli(event.startTime).atZone(ZoneId.systemDefault()).dayOfMonth + "日"
        else -> ""
    }

    fun weekdayCn(iso: Int): String = when (iso) {
        1 -> "一"; 2 -> "二"; 3 -> "三"; 4 -> "四"; 5 -> "五"; 6 -> "六"; 7 -> "日"
        else -> "?"
    }

    /** WEEKLY 用法：ISO 1=周一 … 7=周日 */
    val WEEKDAY_OPTIONS = listOf(1 to "周一", 2 to "周二", 3 to "周三", 4 to "周四", 5 to "周五", 6 to "周六", 7 to "周日")

    /**
     * 展开 [events] 在 [from, to) 时间窗内的所有实例。
     */
    fun expand(events: List<CalendarEventEntity>, from: Long, to: Long): List<Occurrence> {
        val out = ArrayList<Occurrence>()
        events.forEach { e ->
            val dur = (e.endTime - e.startTime).coerceAtLeast(1L)
            when (e.recurrence.uppercase().ifBlank { NONE }) {
                NONE -> if (e.startTime < to && e.endTime > from) out += Occurrence(e, e.startTime, e.endTime)
                DAILY -> step(e, from, to, dur, out) { d -> d.plusDays(1) }
                WEEKLY -> step(e, from, to, dur, out) { d -> d.plusWeeks(1) }
                MONTHLY -> step(e, from, to, dur, out) { d -> d.plusMonths(1) }
                else -> if (e.startTime < to && e.endTime > from) out += Occurrence(e, e.startTime, e.endTime)
            }
        }
        out.sortBy { it.start }
        return out
    }

    /** 某天是否有日程（月视图圆点用） */
    fun colorsOn(events: List<CalendarEventEntity>, date: LocalDate): List<Int> {
        val zone = ZoneId.systemDefault()
        val s = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val e = s + 24L * 60 * 60 * 1000
        return expand(events, s, e).map { it.event.color }
    }

    private fun step(
        e: CalendarEventEntity,
        from: Long,
        to: Long,
        dur: Long,
        out: MutableList<Occurrence>,
        next: (LocalDate) -> LocalDate
    ) {
        val zone = ZoneId.systemDefault()
        val startZdt = Instant.ofEpochMilli(e.startTime).atZone(zone)
        var date = startZdt.toLocalDate()
        // WEEKLY + 指定星期几：把第一个实例对齐到最近的该星期几
        if (e.recurrence.uppercase() == WEEKLY && e.recurrenceWeekday in 1..7) {
            var guard0 = 0
            while (date.dayOfWeek.value != e.recurrenceWeekday && guard0++ < 7) date = date.plusDays(1)
        }
        var guard = 0
        while (guard++ < 1000) {
            val s = date.atTime(startZdt.toLocalTime()).atZone(zone).toInstant().toEpochMilli()
            if (e.recurrenceUntil > 0L && s > e.recurrenceUntil) break
            if (s >= to) break
            if (s + dur > from) out += Occurrence(e, s, s + dur)
            date = next(date)
        }
    }
}
