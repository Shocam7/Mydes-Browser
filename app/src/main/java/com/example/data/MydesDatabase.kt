package com.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.data.dao.MydesDao
import com.example.data.model.BookmarkEntity
import com.example.data.model.DomainColorEntity
import com.example.data.model.HistoryEntity

import com.example.data.model.GeneratedThemeEntity

@Database(
    entities = [BookmarkEntity::class, HistoryEntity::class, DomainColorEntity::class, GeneratedThemeEntity::class],
    version = 3,
    exportSchema = false
)
abstract class MydesDatabase : RoomDatabase() {
    abstract fun mydesDao(): MydesDao

    companion object {
        @Volatile
        private var INSTANCE: MydesDatabase? = null

        val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `generated_themes` ADD COLUMN `activeCss` TEXT")
                db.execSQL("ALTER TABLE `generated_themes` ADD COLUMN `concept` TEXT")
                db.execSQL("ALTER TABLE `generated_themes` ADD COLUMN `chatHistoryJson` TEXT")
                db.execSQL("ALTER TABLE `generated_themes` ADD COLUMN `bg` TEXT")
                db.execSQL("ALTER TABLE `generated_themes` ADD COLUMN `fg` TEXT")
            }
        }

        fun getInstance(context: Context): MydesDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    MydesDatabase::class.java,
                    "mydes_browser.db"
                )
                    .addMigrations(MIGRATION_2_3)
                    .createFromAsset("databases/mydes_browser.db")
                    .fallbackToDestructiveMigration(dropAllTables = false)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
