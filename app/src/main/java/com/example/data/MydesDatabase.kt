package com.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.data.dao.MydesDao
import com.example.data.model.BookmarkEntity
import com.example.data.model.DomainColorEntity
import com.example.data.model.HistoryEntity

@Database(
    entities = [BookmarkEntity::class, HistoryEntity::class, DomainColorEntity::class],
    version = 1,
    exportSchema = false
)
abstract class MydesDatabase : RoomDatabase() {
    abstract fun mydesDao(): MydesDao

    companion object {
        @Volatile
        private var INSTANCE: MydesDatabase? = null

        fun getInstance(context: Context): MydesDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    MydesDatabase::class.java,
                    "mydes_browser.db"
                ).fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
