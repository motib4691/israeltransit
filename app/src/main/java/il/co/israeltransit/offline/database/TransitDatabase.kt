package il.co.israeltransit.offline.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import java.io.*
import java.util.zip.GZIPInputStream

class TransitDatabase(context: Context) {
    private val appContext = context.applicationContext
    private val storage = TransitStorage(appContext)

    private val databaseDirectory: File get() = storage.getSelectedDirectory()
    val databaseFile: File get() = File(databaseDirectory, DATABASE_NAME)
    private val temporaryFile: File get() = File(databaseDirectory, TEMPORARY_NAME)
    private val backupFile: File get() = File(databaseDirectory, BACKUP_NAME)

    data class ImportResult(val success: Boolean, val message: String)

    companion object {
        private const val DATABASE_NAME = "israel-transit.sqlite"
        private const val TEMPORARY_NAME = "israel-transit.tmp"
        private const val BACKUP_NAME = "israel-transit.backup"
        private const val MINIMUM_DATABASE_SIZE = 100L
        private const val BUFFER_SIZE = 64 * 1024
    }

    init {
        databaseDirectory.mkdirs()
    }

    @Synchronized
    fun importDatabase(uri: Uri): ImportResult {
        return try {
            databaseDirectory.mkdirs()
            temporaryFile.delete()

            val rawInput = appContext.contentResolver.openInputStream(uri)
                ?: error("לא ניתן לקרוא את הקובץ שנבחר")

            BufferedInputStream(rawInput, BUFFER_SIZE).use { bufferedInput ->
                PushbackInputStream(bufferedInput, 2).use { pushback ->
                    val signature = ByteArray(2)
                    val count = pushback.read(signature)
                    if (count > 0) pushback.unread(signature, 0, count)

                    val isGzip = count == 2 && signature[0] == 0x1f.toByte() && signature[1] == 0x8b.toByte()
                    val streamToRead = if (isGzip) {
                        BufferedInputStream(GZIPInputStream(pushback, BUFFER_SIZE), BUFFER_SIZE)
                    } else {
                        pushback
                    }

                    BufferedOutputStream(FileOutputStream(temporaryFile), BUFFER_SIZE).use { output ->
                        streamToRead.copyTo(output, bufferSize = BUFFER_SIZE)
                        output.flush()
                    }
                }
            }

            quickValidateDatabase(temporaryFile)

            backupFile.delete()
            if (databaseFile.exists()) {
                databaseFile.renameTo(backupFile)
            }

            if (!temporaryFile.renameTo(databaseFile)) {
                if (backupFile.exists()) backupFile.renameTo(databaseFile)
                error("לא ניתן להתקין את המאגר החדש")
            }

            backupFile.delete()
            ImportResult(true, "מאגר הנתונים הותקן בהצלחה ובמהירות!")
        } catch (e: Exception) {
            temporaryFile.delete()
            if (!databaseFile.exists() && backupFile.exists()) {
                backupFile.renameTo(databaseFile)
            }
            ImportResult(false, "ייבוא המאגר נכשל: ${e.message ?: "שגיאה לא ידועה"}")
        }
    }

    private fun quickValidateDatabase(file: File) {
        require(file.exists() && file.length() > MINIMUM_DATABASE_SIZE) { "הקובץ ריק או קטן מדי" }

        val database = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        try {
            database.rawQuery("PRAGMA quick_check(1)", null).use { cursor ->
                require(cursor.moveToFirst() && cursor.getString(0).equals("ok", ignoreCase = true)) {
                    "בדיקת תקינות SQLite נכשלה"
                }
            }
            for (table in listOf("routes", "trips", "stops", "stop_times", "calendar")) {
                database.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", arrayOf(table)).use {
                    require(it.moveToFirst()) { "חסרה טבלה חיונית: $table" }
                }
            }
        } finally {
            database.close()
        }
    }

    @Synchronized fun isDatabaseAvailable(): Boolean = databaseFile.exists() && databaseFile.length() > MINIMUM_DATABASE_SIZE
    @Synchronized fun getDatabasePath(): String? = if (isDatabaseAvailable()) databaseFile.absolutePath else null
    @Synchronized fun getLastUpdateDate(): Long = if (isDatabaseAvailable()) databaseFile.lastModified() else 0L

    @Synchronized
    fun openReadOnly(): SQLiteDatabase? {
        if (!isDatabaseAvailable()) return null
        return try {
            SQLiteDatabase.openDatabase(databaseFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        } catch (_: Exception) {
            null
        }
    }
}
