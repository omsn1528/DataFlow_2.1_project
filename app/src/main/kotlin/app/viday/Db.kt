package app.viday

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * One finished counting window.
 * src: 0 = system network statistics, 1 = counter snapshots.
 * fl: quality flags (see Eng.F_*), unc: bytes that could not be attributed, cor: 1 = corrected by user.
 */
data class Day(
    val s: Long, val e: Long, val rx: Long, val tx: Long, val lim: Long,
    val src: Int, val fl: Int, val unc: Long, val cor: Int
) {
    val total: Long get() = rx + tx
}

class Db private constructor(c: Context) : SQLiteOpenHelper(c.applicationContext, "df.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE days(s INTEGER PRIMARY KEY, e INTEGER NOT NULL, rx INTEGER NOT NULL, " +
                "tx INTEGER NOT NULL, lim INTEGER NOT NULL, src INTEGER NOT NULL, fl INTEGER NOT NULL, " +
                "unc INTEGER NOT NULL, cor INTEGER NOT NULL)"
        )
    }

    // Version 1 is the first schema. Later versions add ALTER TABLE statements here.
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    private fun toDay(q: android.database.Cursor) = Day(
        q.getLong(0), q.getLong(1), q.getLong(2), q.getLong(3), q.getLong(4),
        q.getInt(5), q.getInt(6), q.getLong(7), q.getInt(8)
    )

    private val cols = arrayOf("s", "e", "rx", "tx", "lim", "src", "fl", "unc", "cor")

    @Synchronized
    fun all(): List<Day> {
        val out = ArrayList<Day>()
        readableDatabase.query("days", cols, null, null, null, null, "s DESC").use { q ->
            while (q.moveToNext()) out.add(toDay(q))
        }
        return out
    }

    @Synchronized
    fun get(s: Long): Day? {
        readableDatabase.query("days", cols, "s=?", arrayOf(s.toString()), null, null, null).use { q ->
            return if (q.moveToFirst()) toDay(q) else null
        }
    }

    /** Saves a record unless the user has corrected it manually. */
    @Synchronized
    fun save(d: Day) {
        val old = get(d.s)
        if (old != null && old.cor == 1) return
        force(d)
    }

    @Synchronized
    fun force(d: Day) {
        val v = ContentValues()
        v.put("s", d.s); v.put("e", d.e); v.put("rx", d.rx); v.put("tx", d.tx); v.put("lim", d.lim)
        v.put("src", d.src); v.put("fl", d.fl); v.put("unc", d.unc); v.put("cor", d.cor)
        writableDatabase.insertWithOnConflict("days", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** Replaces every record in one transaction, so a failure leaves the old history untouched. */
    @Synchronized
    fun replaceAll(list: List<Day>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("days", null, null)
            for (d in list) {
                val v = ContentValues()
                v.put("s", d.s); v.put("e", d.e); v.put("rx", d.rx); v.put("tx", d.tx); v.put("lim", d.lim)
                v.put("src", d.src); v.put("fl", d.fl); v.put("unc", d.unc); v.put("cor", d.cor)
                db.insertWithOnConflict("days", null, v, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun delete(s: Long) {
        writableDatabase.delete("days", "s=?", arrayOf(s.toString()))
    }

    @Synchronized
    fun clear() {
        writableDatabase.delete("days", null, null)
    }

    companion object {
        @Volatile private var inst: Db? = null
        fun get(c: Context): Db = inst ?: synchronized(this) {
            inst ?: Db(c.applicationContext).also { inst = it }
        }
    }
}
