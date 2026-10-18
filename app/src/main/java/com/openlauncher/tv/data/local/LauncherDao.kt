package com.openlauncher.tv.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface LauncherDao {

    // === 分类操作 ===
    @Query("SELECT * FROM categories ORDER BY sortOrder ASC")
    fun getAllCategoriesFlow(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories ORDER BY sortOrder ASC")
    suspend fun getAllCategories(): List<CategoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCategory(category: CategoryEntity): Long

    @Update
    suspend fun updateCategory(category: CategoryEntity)

    @Delete
    suspend fun deleteCategory(category: CategoryEntity)

    @Query("SELECT COUNT(*) FROM categories")
    suspend fun getCategoryCount(): Int

    // === 应用项操作 ===
    @Query("SELECT * FROM app_items WHERE isHidden = 0 ORDER BY sortWeight ASC, label ASC")
    fun getAllVisibleAppsFlow(): Flow<List<AppItemEntity>>

    @Query("SELECT * FROM app_items WHERE categoryId = :categoryId AND isHidden = 0 ORDER BY sortWeight ASC, label ASC")
    fun getAppsByCategoryIdFlow(categoryId: Long): Flow<List<AppItemEntity>>

    @Query("SELECT * FROM app_items WHERE isHidden = 1 ORDER BY label ASC")
    fun getHiddenAppsFlow(): Flow<List<AppItemEntity>>

    @Query("SELECT * FROM app_items WHERE packageName = :packageName AND activityName = :activityName LIMIT 1")
    suspend fun findApp(packageName: String, activityName: String): AppItemEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateApp(app: AppItemEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAppsIfNotExist(apps: List<AppItemEntity>)

    @Update
    suspend fun updateApp(app: AppItemEntity)

    @Query("DELETE FROM app_items WHERE packageName = :packageName")
    suspend fun deleteAppsByPackage(packageName: String)

    @Query("SELECT * FROM app_items WHERE isWhitelistForKids = 1")
    suspend fun getKidWhitelistApps(): List<AppItemEntity>
}
