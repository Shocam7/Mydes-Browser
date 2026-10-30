package com.example.data

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * Ensures that pre-packaged user designs, screenshots, request logs, and shared preferences
 * from the app assets are seeded onto device storage on first run or when missing.
 */
object PrepackageDataSeeder {
    private const val TAG = "PrepackageDataSeeder"

    fun seedIfNeeded(context: Context) {
        try {
            seedSharedPreferences(context)
            seedScreenshots(context)
            seedRequestLogs(context)
            seedDatabase(context)
        } catch (e: Exception) {
            Log.e(TAG, "Error seeding prepackaged data", e)
        }
    }

    private fun seedDatabase(context: Context) {
        val dbFile = context.getDatabasePath("mydes_browser.db")
        val walFile = File(dbFile.path + "-wal")
        val shmFile = File(dbFile.path + "-shm")
        var shouldCopy = !dbFile.exists() || dbFile.length() == 0L

        if (!shouldCopy && dbFile.exists()) {
            try {
                val db = android.database.sqlite.SQLiteDatabase.openDatabase(
                    dbFile.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY
                )
                db.use {
                    val cursor = it.rawQuery("SELECT count(*) FROM generated_themes", null)
                    cursor.use { c ->
                        if (c.moveToFirst() && c.getInt(0) == 0) {
                            shouldCopy = true
                        }
                    }
                }
            } catch (_: Exception) {
                shouldCopy = true
            }
        }

        if (shouldCopy) {
            dbFile.parentFile?.mkdirs()
            try { if (walFile.exists()) walFile.delete() } catch (_: Exception) {}
            try { if (shmFile.exists()) shmFile.delete() } catch (_: Exception) {}
            copyAssetFile(context, "databases/mydes_browser.db", dbFile)
            Log.i(TAG, "Prepackaged database seeded to ${dbFile.absolutePath}")
        }
    }

    private fun seedRequestLogs(context: Context) {
        val logFile = File(context.filesDir, "request_logs.json")
        if (!logFile.exists() || logFile.length() == 0L) {
            logFile.parentFile?.mkdirs()
            copyAssetFile(context, "files/request_logs.json", logFile)
            Log.i(TAG, "Prepackaged request_logs.json seeded")
        }
    }

    private fun seedScreenshots(context: Context) {
        val themeDir = File(context.filesDir, "theme_screenshots")
        val reviewDir = File(context.filesDir, "review_screenshots")
        if (!themeDir.exists()) themeDir.mkdirs()
        if (!reviewDir.exists()) reviewDir.mkdirs()

        try {
            val assetFiles = context.assets.list("files/theme_screenshots") ?: emptyArray()
            for (filename in assetFiles) {
                val themeDest = File(themeDir, filename)
                if (!themeDest.exists() || themeDest.length() == 0L) {
                    copyAssetFile(context, "files/theme_screenshots/$filename", themeDest)
                }
                val reviewDest = File(reviewDir, filename)
                if (!reviewDest.exists() || reviewDest.length() == 0L) {
                    copyAssetFile(context, "files/theme_screenshots/$filename", reviewDest)
                }
            }
            Log.i(TAG, "Seeded ${assetFiles.size} screenshots to theme_screenshots and review_screenshots")
        } catch (e: Exception) {
            Log.w(TAG, "Could not list theme_screenshots from assets", e)
        }
    }

    private fun seedSharedPreferences(context: Context) {
        try {
            val prefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
            if (!prefsDir.exists()) prefsDir.mkdirs()

            val spFiles = context.assets.list("shared_prefs") ?: emptyArray()
            for (filename in spFiles) {
                val destFile = File(prefsDir, filename)
                if (!destFile.exists() || destFile.length() == 0L) {
                    copyAssetFile(context, "shared_prefs/$filename", destFile)
                }
            }
            Log.i(TAG, "Seeded shared preferences XML files")
        } catch (e: Exception) {
            Log.w(TAG, "Could not seed shared_prefs", e)
        }
    }

    private fun copyAssetFile(context: Context, assetPath: String, destFile: File) {
        try {
            context.assets.open(assetPath).use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to copy asset $assetPath to ${destFile.name}: ${e.message}")
        }
    }
}
