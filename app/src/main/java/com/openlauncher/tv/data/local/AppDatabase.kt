package com.openlauncher.tv.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Database(
    entities = [CategoryEntity::class, AppItemEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun launcherDao(): LauncherDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context, scope: CoroutineScope): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "open_tv_launcher.db"
                )
                    .addCallback(AppDatabaseCallback(scope))
                    .build()
                INSTANCE = instance
                instance
            }
        }

        private class AppDatabaseCallback(
            private val scope: CoroutineScope
        ) : RoomDatabase.Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                super.onCreate(db)
                INSTANCE?.let { database ->
                    scope.launch(Dispatchers.IO) {
                        populateInitialCategories(database.launcherDao())
                    }
                }
            }

            suspend fun populateInitialCategories(dao: LauncherDao) {
                // 初始化系统预置默认分类
                dao.insertCategory(CategoryEntity(name = "常用应用", sortOrder = 0, isSystemDefault = true))
                dao.insertCategory(CategoryEntity(name = "影音播放", sortOrder = 1, isSystemDefault = false))
                dao.insertCategory(CategoryEntity(name = "电视游戏", sortOrder = 2, isSystemDefault = false))
                dao.insertCategory(CategoryEntity(name = "系统工具", sortOrder = 3, isSystemDefault = false))
            }
        }
    }
}
