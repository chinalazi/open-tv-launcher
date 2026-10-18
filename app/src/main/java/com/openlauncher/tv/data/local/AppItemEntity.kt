package com.openlauncher.tv.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 桌面应用配置映射表
 */
@Entity(
    tableName = "app_items",
    indices = [
        Index(value = ["packageName", "activityName"], unique = true),
        Index(value = ["categoryId"])
    ]
)
data class AppItemEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val packageName: String,
    val activityName: String,
    val label: String,
    val categoryId: Long,
    val sortWeight: Int = 0,
    val isHidden: Boolean = false,
    val isWhitelistForKids: Boolean = false // 是否为儿童模式白名单应用（不限时）
)
