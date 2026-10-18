package com.openlauncher.tv.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 应用分类实体表
 */
@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val sortOrder: Int = 0,
    val isChildVisible: Boolean = true,
    val isSystemDefault: Boolean = false
)
