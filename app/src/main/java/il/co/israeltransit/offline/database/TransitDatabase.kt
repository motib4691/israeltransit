package il.co.israeltransit.offline.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import java.io.*
import java.time.DayOfWeek
import java.time.LocalDate
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
        const val SUPPORTED_FORMAT_VERSION = 6
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

            val formatVersion = TransitV6Codec.readFormatVersion(database)
            require(formatVersion >= SUPPORTED_FORMAT_VERSION) {
                "גרסת מאגר הנתונים אינה נתמכת (נדרשת גרסה $SUPPORTED_FORMAT_VERSION). יש להוריד קובץ נתונים מעודכן."
            }

            for (table in TransitV6Codec.REQUIRED_TABLES) {
                database.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", arrayOf(table)).use {
                    require(it.moveToFirst()) { "חסרה טבלה חיונית: $table" }
                }
            }

            database.rawQuery(
                "SELECT (SELECT COUNT(*) FROM route_patterns), (SELECT COUNT(*) FROM pattern_schedules)",
                null
            ).use { cursor ->
                require(cursor.moveToFirst() && cursor.getLong(0) > 0L && cursor.getLong(0) == cursor.getLong(1)) {
                    "נתוני המסלולים ולוחות הזמנים במאגר אינם תקינים"
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
    fun getDatabaseStamp(): String {
        val file = databaseFile
        return if (file.exists()) {
            "${file.absolutePath}|${file.lastModified()}|${file.length()}"
        } else {
            ""
        }
    }

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

object TransitV6Codec {

    val REQUIRED_TABLES = listOf(
        "agency",
        "routes",
        "stops",
        "calendar",
        "calendar_dates",
        "headsigns",
        "route_patterns",
        "route_pattern_stops",
        "timing_profiles",
        "pattern_schedules"
    )

    class DecodedProfile(
        val arrOffsets: IntArray,
        val depOffsets: IntArray
    )

    class DecodedSchedule(
        val startSecs: IntArray,
        val serviceIndices: IntArray,
        val profileIds: IntArray,
        val headsignIndices: IntArray
    )

    fun readFormatVersion(db: SQLiteDatabase): Int {
        return try {
            db.rawQuery(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name='import_metadata'",
                null
            ).use { exists ->
                if (!exists.moveToFirst()) return 0
            }
            db.rawQuery(
                "SELECT value FROM import_metadata WHERE key = 'format_version' LIMIT 1",
                null
            ).use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0)?.toIntOrNull() ?: 0 else 0
            }
        } catch (_: Exception) {
            0
        }
    }

    fun readUvarint(buf: ByteArray, startPos: Int): Long {
        var result = 0
        var shift = 0
        var pos = startPos
        while (true) {
            val b = buf[pos++].toInt() and 0xFF
            result = result or ((b and 0x7F) shl shift)
            if (b < 0x80) {
                return (result.toLong() shl 32) or (pos.toLong() and 0xFFFFFFFFL)
            }
            shift += 7
        }
    }

    fun readSvarint(buf: ByteArray, startPos: Int): Long {
        val packed = readUvarint(buf, startPos)
        val raw = (packed shr 32).toInt()
        val pos = packed.toInt()
        val signed = (raw ushr 1) xor -(raw and 1)
        return (signed.toLong() shl 32) or (pos.toLong() and 0xFFFFFFFFL)
    }

    fun decodeProfile(stopCount: Int, blob: ByteArray): DecodedProfile {
        val arrOffsets = IntArray(stopCount)
        val depOffsets = IntArray(stopCount)
        var pos = 0
        var prevDep = 0
        for (i in 0 until stopCount) {
            val pArr = readSvarint(blob, pos)
            val dArr = (pArr shr 32).toInt()
            pos = pArr.toInt()

            val pDwell = readSvarint(blob, pos)
            val dwell = (pDwell shr 32).toInt()
            pos = pDwell.toInt()

            val arrOff = prevDep + dArr
            val depOff = arrOff + dwell
            arrOffsets[i] = arrOff
            depOffsets[i] = depOff
            prevDep = depOff
        }
        return DecodedProfile(arrOffsets, depOffsets)
    }

    fun decodeSchedule(tripCount: Int, blob: ByteArray): DecodedSchedule {
        val startSecs = IntArray(tripCount)
        val serviceIndices = IntArray(tripCount)
        val profileIds = IntArray(tripCount)
        val headsignIndices = IntArray(tripCount)
        var pos = 0
        var prevStart = 0
        for (i in 0 until tripCount) {
            val pStart = readUvarint(blob, pos)
            val curStart = prevStart + (pStart shr 32).toInt()
            pos = pStart.toInt()

            val pService = readUvarint(blob, pos)
            val serviceIdx = (pService shr 32).toInt()
            pos = pService.toInt()

            val pProfile = readUvarint(blob, pos)
            val profileId = (pProfile shr 32).toInt()
            pos = pProfile.toInt()

            val pHeadsign = readUvarint(blob, pos)
            val headsignIdx = (pHeadsign shr 32).toInt()
            pos = pHeadsign.toInt()

            startSecs[i] = curStart
            serviceIndices[i] = serviceIdx
            profileIds[i] = profileId
            headsignIndices[i] = headsignIdx
            prevStart = curStart
        }
        return DecodedSchedule(startSecs, serviceIndices, profileIds, headsignIndices)
    }

    fun toDateInt(date: LocalDate): Int {
        return date.year * 10000 + date.monthValue * 100 + date.dayOfMonth
    }

    fun fromDateInt(dateInt: Int): LocalDate? {
        if (dateInt < 19000101 || dateInt > 29991231) return null
        return try {
            LocalDate.of(dateInt / 10000, (dateInt / 100) % 100, dateInt % 100)
        } catch (_: Exception) {
            null
        }
    }

    fun weekdayBit(dayOfWeek: DayOfWeek): Int = 1 shl (dayOfWeek.value - 1)

    fun queryDateBounds(db: SQLiteDatabase): Pair<Int, Int>? {
        return try {
            db.rawQuery(
                "SELECT MIN(start_date), MAX(end_date) FROM calendar",
                null
            ).use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0) && !cursor.isNull(1)) {
                    val minD = cursor.getInt(0)
                    val maxD = cursor.getInt(1)
                    if (minD in 19000101..maxD) minD to maxD else null
                } else null
            }
        } catch (_: Exception) {
            null
        }
    }

    fun resolveEffectiveDate(
        db: SQLiteDatabase,
        targetDate: LocalDate,
        bounds: Pair<Int, Int>? = queryDateBounds(db)
    ): LocalDate {
        if (bounds == null) return targetDate
        val (minDateInt, maxDateInt) = bounds
        val targetInt = toDateInt(targetDate)
        if (targetInt in minDateInt..maxDateInt) {
            return targetDate
        }

        val minDate = fromDateInt(minDateInt) ?: return targetDate
        val maxDate = fromDateInt(maxDateInt) ?: return targetDate
        val anchor = if (targetInt > maxDateInt) maxDate else minDate
        val desiredDow = targetDate.dayOfWeek
        val bit = weekdayBit(desiredDow)

        var candidate = anchor
        val step = if (targetInt > maxDateInt) -1L else 1L
        var foundFirst: LocalDate? = null
        for (i in 0 until 7) {
            if (candidate.dayOfWeek == desiredDow) {
                foundFirst = candidate
                break
            }
            candidate = candidate.plusDays(step)
        }
        val base = foundFirst ?: return targetDate

        // Search up to 3 matching weekdays inside [minDate, maxDate] to pick a full active week
        var bestDate = base
        var bestCount = -1
        for (weekOffset in 0L..2L) {
            val checkDate = base.plusDays(weekOffset * 7L * step)
            val checkInt = toDateInt(checkDate)
            if (checkInt !in minDateInt..maxDateInt) break
            val count = try {
                db.rawQuery(
                    """
                    SELECT COUNT(*) FROM calendar
                    WHERE start_date <= ? AND end_date >= ? AND (weekday_mask & ?) != 0
                    """.trimIndent(),
                    arrayOf(checkInt.toString(), checkInt.toString(), bit.toString())
                ).use { c ->
                    if (c.moveToFirst()) c.getInt(0) else 0
                }
            } catch (_: Exception) {
                0
            }
            if (count > bestCount) {
                bestCount = count
                bestDate = checkDate
            }
        }
        return bestDate
    }

    fun loadActiveServiceIndices(
        db: SQLiteDatabase,
        effectiveDate: LocalDate
    ): Set<Int> {
        val dateInt = toDateInt(effectiveDate)
        val dateStr = dateInt.toString()
        val bit = weekdayBit(effectiveDate.dayOfWeek).toString()
        val result = HashSet<Int>(512)

        db.rawQuery(
            """
            SELECT service_idx FROM calendar
            WHERE start_date <= ? AND end_date >= ? AND (weekday_mask & ?) != 0
            """.trimIndent(),
            arrayOf(dateStr, dateStr, bit)
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result.add(cursor.getInt(0))
            }
        }

        try {
            db.rawQuery(
                "SELECT service_idx, exception_type FROM calendar_dates WHERE date = ?",
                arrayOf(dateStr)
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val sIdx = cursor.getInt(0)
                    val exType = cursor.getInt(1)
                    if (exType == 1) {
                        result.add(sIdx)
                    } else if (exType == 2) {
                        result.remove(sIdx)
                    }
                }
            }
        } catch (_: Exception) {
        }

        return result
    }
}
