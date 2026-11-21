package com.magicnote.mgxd.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 日历日程事件实体
 * @param source 创建来源（"magic_ai"=由 Magic AI 创建，空串=手动创建）
 */
@Entity(tableName = "events")
data class CalendarEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val startTime: Long,
    val endTime: Long,
    val description: String? = null,
    val color: Int = 0xFF7C4DFF.toInt(),
    val createdAt: Long = System.currentTimeMillis(),
    val source: String = "",
    /** 提前提醒分钟数（0=不提醒，5/10/15/30/60/120/1440 等） */
    val remindMinutes: Int = 0,
    /** 循环规则：NONE / DAILY / WEEKLY / MONTHLY（循环日程只存一条，渲染时展开）
     *  default 值与 MIGRATION_8_9 的 ALTER TABLE ... DEFAULT 保持一致，避免 Room 迁移校验不一致 */
    @ColumnInfo(defaultValue = "NONE") val recurrence: String = "NONE",
    /** WEEKLY 指定星期几（ISO：1=周一 … 7=周日；0=跟随起始日期） */
    @ColumnInfo(defaultValue = "0") val recurrenceWeekday: Int = 0,
    /** 循环截止时间（毫秒，0=永久） */
    @ColumnInfo(defaultValue = "0") val recurrenceUntil: Long = 0L
)