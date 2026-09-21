package com.blackhole.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class HistoryItem(val id: Long, val uri: String, val title: String, val source: String, val quality: String, val bytes: Long, val date: Long)
class HistoryStore(context: Context) : SQLiteOpenHelper(context, "history.db", null, 1), java.io.Closeable {
    override fun close() { super.close() }
    override fun onCreate(db: SQLiteDatabase) { db.execSQL("CREATE TABLE downloads (id INTEGER PRIMARY KEY, uri TEXT NOT NULL, title TEXT NOT NULL, source TEXT NOT NULL, quality TEXT NOT NULL, bytes INTEGER NOT NULL, date INTEGER NOT NULL)") }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    fun add(uri: String, video: Video, quality: String, size: Long) {
        val values = ContentValues().apply {
            put("uri", uri); put("title", video.title.take(200)); put("source", video.source)
            put("quality", quality); put("bytes", size); put("date", System.currentTimeMillis())
        }
        check(writableDatabase.insert("downloads", null, values) != -1L) { "Cannot record download" }
    }
    fun list(): List<HistoryItem> = readableDatabase.rawQuery("SELECT * FROM downloads ORDER BY date DESC", null).use { c ->
        buildList { while(c.moveToNext()) add(HistoryItem(c.getLong(0),c.getString(1),c.getString(2),c.getString(3),c.getString(4),c.getLong(5),c.getLong(6))) }
    }
    fun remove(id: Long) { writableDatabase.delete("downloads", "id=?", arrayOf(id.toString())) }
}
