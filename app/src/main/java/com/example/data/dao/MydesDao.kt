package com.example.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.model.BookmarkEntity
import com.example.data.model.DomainColorEntity
import com.example.data.model.HistoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MydesDao {
    // Bookmarks
    @Query("SELECT * FROM bookmarks ORDER BY createdAt DESC")
    fun getAllBookmarks(): Flow<List<BookmarkEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM bookmarks WHERE url = :url)")
    fun isBookmarked(url: String): Flow<Boolean>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBookmark(bookmark: BookmarkEntity): Long

    @Delete
    suspend fun deleteBookmark(bookmark: BookmarkEntity)

    @Query("DELETE FROM bookmarks WHERE url = :url")
    suspend fun deleteBookmarkByUrl(url: String)

    // History
    @Query("SELECT * FROM history ORDER BY visitedAt DESC LIMIT :limit")
    fun getRecentHistory(limit: Int = 100): Flow<List<HistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHistory(history: HistoryEntity): Long

    @Delete
    suspend fun deleteHistory(history: HistoryEntity)

    @Query("DELETE FROM history")
    suspend fun clearAllHistory()

    // Domain Colors
    @Query("SELECT * FROM domain_colors WHERE domain = :domain LIMIT 1")
    suspend fun getDomainColor(domain: String): DomainColorEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDomainColor(domainColor: DomainColorEntity)

    // Generated Themes & Screenshots
    @Query("SELECT * FROM generated_themes WHERE domain = :domain ORDER BY createdAt DESC")
    fun getGeneratedThemesForDomain(domain: String): Flow<List<com.example.data.model.GeneratedThemeEntity>>

    @Query("SELECT * FROM generated_themes ORDER BY createdAt DESC")
    fun getAllGeneratedThemes(): Flow<List<com.example.data.model.GeneratedThemeEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGeneratedTheme(theme: com.example.data.model.GeneratedThemeEntity): Long

    @androidx.room.Update
    suspend fun updateGeneratedTheme(theme: com.example.data.model.GeneratedThemeEntity)

    @Delete
    suspend fun deleteGeneratedTheme(theme: com.example.data.model.GeneratedThemeEntity)

    @Query("DELETE FROM generated_themes WHERE id = :id")
    suspend fun deleteGeneratedThemeById(id: Long)
}
