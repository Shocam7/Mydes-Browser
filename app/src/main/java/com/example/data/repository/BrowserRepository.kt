package com.example.data.repository

import com.example.data.dao.MydesDao
import com.example.data.model.BookmarkEntity
import com.example.data.model.HistoryEntity
import kotlinx.coroutines.flow.Flow

class BrowserRepository(private val mydesDao: MydesDao) {

    val bookmarks: Flow<List<BookmarkEntity>> = mydesDao.getAllBookmarks()
    val history: Flow<List<HistoryEntity>> = mydesDao.getRecentHistory(100)

    fun isBookmarked(url: String): Flow<Boolean> = mydesDao.isBookmarked(url)

    suspend fun addBookmark(title: String, url: String) {
        mydesDao.insertBookmark(BookmarkEntity(title = title, url = url))
    }

    suspend fun removeBookmark(url: String) {
        mydesDao.deleteBookmarkByUrl(url)
    }

    suspend fun addHistory(title: String, url: String, isIncognito: Boolean) {
        if (isIncognito) return // Do not record history for incognito tabs
        mydesDao.insertHistory(HistoryEntity(title = title, url = url))
    }

    suspend fun deleteHistory(item: HistoryEntity) {
        mydesDao.deleteHistory(item)
    }

    suspend fun clearHistory() {
        mydesDao.clearAllHistory()
    }
}
