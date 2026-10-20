package com.openlauncher.tv.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [CategoryEntity::class, AppItemEntity::class],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun launcherDao(): LauncherDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * v2 -> v3：移除儿童白名单字段（防沉迷模块已删除）。
         * 老库里可能没有该列（历史版本号一致但结构不同），所以整段包在 try 里，
         * 失败就重建空表 —— 桌面会重新扫描应用，比崩溃好。
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                try {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `app_items_new` (" +
                            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`packageName` TEXT NOT NULL, " +
                            "`activityName` TEXT NOT NULL, " +
                            "`label` TEXT NOT NULL, " +
                            "`categoryId` INTEGER NOT NULL, " +
                            "`sortWeight` INTEGER NOT NULL, " +
                            "`isHidden` INTEGER NOT NULL)"
                    )
                    db.execSQL(
                        "INSERT OR REPLACE INTO `app_items_new` " +
                            "(`id`, `packageName`, `activityName`, `label`, `categoryId`, `sortWeight`, `isHidden`) " +
                            "SELECT `id`, `packageName`, `activityName`, `label`, `categoryId`, `sortWeight`, `isHidden` " +
                            "FROM `app_items`"
                    )
                    db.execSQL("DROP TABLE `app_items`")
                    db.execSQL("ALTER TABLE `app_items_new` RENAME TO `app_items`")
                } catch (e: Exception) {
                    db.execSQL("DROP TABLE IF EXISTS `app_items_new`")
                    db.execSQL("DROP TABLE IF EXISTS `app_items`")
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `app_items` (" +
                            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`packageName` TEXT NOT NULL, " +
                            "`activityName` TEXT NOT NULL, " +
                            "`label` TEXT NOT NULL, " +
                            "`categoryId` INTEGER NOT NULL, " +
                            "`sortWeight` INTEGER NOT NULL, " +
                            "`isHidden` INTEGER NOT NULL)"
                    )
                }
                // 表重建后索引随之丢失，需按 Room 期望的名字重建
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_app_items_packageName_activityName` " +
                        "ON `app_items` (`packageName`, `activityName`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_app_items_categoryId` " +
                        "ON `app_items` (`categoryId`)"
                )
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "open_tv_launcher.db"
                )
                    .addMigrations(MIGRATION_2_3)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
