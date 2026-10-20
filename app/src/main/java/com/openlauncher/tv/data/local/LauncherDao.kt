package com.openlauncher.tv.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface LauncherDao {

    // === 分类操作 ===
    @Query("SELECT * FROM categories ORDER BY sortOrder ASC")
    fun getAllCategoriesFlow(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories ORDER BY sortOrder ASC")
    suspend fun getAllCategories(): List<CategoryEntity>

    @Query("SELECT COALESCE(MAX(sortOrder), -1) FROM categories")
    suspend fun maxSortOrder(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCategory(category: CategoryEntity): Long

    @Update
    suspend fun updateCategory(category: CategoryEntity)

    @Delete
    suspend fun deleteCategory(category: CategoryEntity)

    @Query("UPDATE app_items SET categoryId = :newCategoryId WHERE categoryId = :oldCategoryId")
    suspend fun moveAppsToCategory(oldCategoryId: Long, newCategoryId: Long)

    // === 应用项操作 ===
    @Query("SELECT * FROM app_items")
    suspend fun getAllApps(): List<AppItemEntity>

    @Query("SELECT * FROM app_items WHERE isHidden = 0 ORDER BY sortWeight ASC, label ASC")
    fun getAllVisibleAppsFlow(): Flow<List<AppItemEntity>>

    @Query("SELECT * FROM app_items WHERE isHidden = 1 ORDER BY label ASC")
    fun getHiddenAppsFlow(): Flow<List<AppItemEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAppsIfNotExist(apps: List<AppItemEntity>)

    @Query("UPDATE app_items SET label = :label WHERE id = :id")
    suspend fun updateLabel(id: Long, label: String)

    @Query("UPDATE app_items SET isHidden = :hidden WHERE id = :id")
    suspend fun setHidden(id: Long, hidden: Boolean)

    @Query("UPDATE app_items SET categoryId = :categoryId WHERE id = :id")
    suspend fun setCategory(id: Long, categoryId: Long)

    @Query("DELETE FROM app_items WHERE packageName = :packageName")
    suspend fun deleteAppsByPackage(packageName: String)

    @Query("DELETE FROM app_items WHERE packageName = :packageName AND activityName = :activityName")
    suspend fun deleteAppComponent(packageName: String, activityName: String)
}
