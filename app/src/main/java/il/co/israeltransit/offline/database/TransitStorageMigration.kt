package il.co.israeltransit.offline.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.StatFs
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

class TransitStorageMigration(context: Context) {

    private val appContext = context.applicationContext
    private val storage = TransitStorage(appContext)

    data class MigrationResult(
        val success: Boolean,
        val message: String
    )

    private data class Asset(
        val source: File,
        val target: File,
        val temporary: File,
        val backup: File,
        val isDirectory: Boolean,
        val validate: (File) -> Unit
    )

    companion object {
        private const val DATABASE_NAME = "israel-transit.sqlite"
        private const val MAP_NAME = "israel-basemap.pmtiles"
        private const val ADDRESSES_NAME = "israel-addresses.sqlite"
        private const val GRAPHHOPPER_DIR_NAME = "graphhopper-foot"
        private const val EXTRA_SPACE = 50L * 1024L * 1024L
    }

    @Synchronized
    fun moveTo(locationId: String): MigrationResult {
        val sourceLocation = storage.getSelectedLocation()
            ?: return failure("לא ניתן לזהות את מיקום האחסון הנוכחי")

        val targetLocation = storage.availableLocations()
            .firstOrNull { it.id == locationId }
            ?: return failure("מיקום האחסון שנבחר אינו זמין")

        if (sourceLocation.id == targetLocation.id) {
            return MigrationResult(
                true,
                "הקבצים כבר נמצאים במיקום שנבחר"
            )
        }

        val sourceDirectory = sourceLocation.directory
        val targetDirectory = targetLocation.directory

        val sourceDatabase = File(sourceDirectory, DATABASE_NAME)
        val targetDatabase = File(targetDirectory, DATABASE_NAME)

        val sourceMap = File(sourceDirectory, MAP_NAME)
        val targetMap = File(targetDirectory, MAP_NAME)

        val sourceAddresses = File(sourceDirectory, ADDRESSES_NAME)
        val targetAddresses = File(targetDirectory, ADDRESSES_NAME)

        val sourceGraphHopper = File(sourceDirectory, GRAPHHOPPER_DIR_NAME)

        val databaseExists = sourceDatabase.isFile
        val mapExists = sourceMap.isFile
        val addressesExists = sourceAddresses.isFile
        val graphHopperExists = sourceGraphHopper.isDirectory && !(sourceGraphHopper.listFiles().isNullOrEmpty())

        if (!databaseExists && !targetDatabase.isFile && !mapExists && !targetMap.isFile) {
            return failure("לא נמצאו מאגר תחבורה או קבצי מפה תקינים להעברה")
        }

        return try {
            if (!targetDirectory.exists() && !targetDirectory.mkdirs()) {
                error("לא ניתן ליצור תיקייה במיקום החדש")
            }

            if (databaseExists) {
                validateDatabase(sourceDatabase)
            } else if (targetDatabase.isFile) {
                validateDatabase(targetDatabase)
            }

            if (mapExists) {
                validatePmtiles(sourceMap)
            } else if (targetMap.exists()) {
                validatePmtiles(targetMap)
            }

            if (addressesExists) {
                validateAddressesDatabase(sourceAddresses)
            } else if (targetAddresses.exists()) {
                validateAddressesDatabase(targetAddresses)
            }

            if (graphHopperExists) {
                validateDirectory(sourceGraphHopper)
            }

            val assets = mutableListOf<Asset>()

            if (databaseExists) {
                assets += createAsset(
                    sourceDatabase,
                    targetDatabase,
                    isDirectory = false,
                    ::validateDatabase
                )
            }

            if (mapExists) {
                assets += createAsset(
                    sourceMap,
                    targetMap,
                    isDirectory = false,
                    ::validatePmtiles
                )
            }

            if (addressesExists) {
                assets += createAsset(
                    sourceAddresses,
                    targetAddresses,
                    isDirectory = false,
                    ::validateAddressesDatabase
                )
            }

            if (graphHopperExists) {
                assets += createAsset(
                    sourceGraphHopper,
                    File(targetDirectory, GRAPHHOPPER_DIR_NAME),
                    isDirectory = true,
                    ::validateDirectory
                )
            }

            if (assets.isEmpty()) {
                return failure("לא נמצאו קבצים להעברה")
            }

            val requiredBytes = assets.sumOf {
                calculateSize(it.source)
            } + EXTRA_SPACE

            val availableBytes = StatFs(
                targetDirectory.absolutePath
            ).availableBytes

            if (availableBytes < requiredBytes) {
                error("אין מספיק מקום פנוי להעברת כל הקבצים למיקום החדש")
            }

            for (asset in assets) {
                if (asset.backup.exists()) {
                    error(
                        "נמצא קובץ גיבוי קודם: ${asset.backup.name}. " +
                            "לא נמחק קובץ זה באופן אוטומטי"
                    )
                }

                if (asset.temporary.exists() && !deleteRecursively(asset.temporary)) {
                    error("לא ניתן לנקות קובץ העברה זמני")
                }
            }

            val installed = mutableListOf<Asset>()

            try {
                // Copy and validate every source before replacing targets.
                for (asset in assets) {
                    if (asset.isDirectory) {
                        copyDirectory(asset.source, asset.temporary)
                        asset.validate(asset.temporary)
                        check(
                            calculateSize(asset.temporary) == calculateSize(asset.source)
                        ) {
                            "גודל התיקייה שהועתקה אינו תואם למקור"
                        }
                    } else {
                        copyFile(asset.source, asset.temporary)
                        asset.validate(asset.temporary)
                        check(
                            asset.temporary.length() == asset.source.length()
                        ) {
                            "גודל הקובץ שהועתק אינו תואם למקור"
                        }
                    }
                }

                // Preserve existing target files before installing replacements.
                for (asset in assets) {
                    if (asset.target.exists()) {
                        check(
                            if (asset.isDirectory) asset.target.isDirectory else asset.target.isFile
                        ) {
                            "היעד הקיים אינו תקין"
                        }

                        check(asset.target.renameTo(asset.backup)) {
                            "לא ניתן לשמור זמנית את הנתונים הקיימים"
                        }
                    }

                    check(asset.temporary.renameTo(asset.target)) {
                        "לא ניתן להתקין את הקובץ במיקום החדש"
                    }

                    installed += asset
                }

                // Validate the complete destination before selecting it.
                for (asset in assets) {
                    asset.validate(asset.target)
                }

                if (targetDatabase.isFile) {
                    validateDatabase(targetDatabase)
                }

                if (targetMap.exists()) {
                    validatePmtiles(targetMap)
                }

                check(storage.selectLocation(locationId)) {
                    "לא ניתן לשמור את מיקום האחסון החדש"
                }
            } catch (exception: Exception) {
                // Restore the old selection and destination files.
                storage.selectLocation(sourceLocation.id)

                for (asset in installed.asReversed()) {
                    deleteRecursively(asset.target)

                    if (asset.backup.exists()) {
                        asset.backup.renameTo(asset.target)
                    }
                }

                for (asset in assets) {
                    deleteRecursively(asset.temporary)

                    if (
                        asset.backup.exists() &&
                        !asset.target.exists()
                    ) {
                        asset.backup.renameTo(asset.target)
                    }
                }

                throw exception
            }

            // The new location is active and all destination files were validated.
            // Only now remove the old source files.
            val undeletedSources = mutableListOf<String>()

            for (asset in assets) {
                if (
                    !deleteRecursively(asset.source) ||
                    asset.source.exists()
                ) {
                    undeletedSources += asset.source.name
                }
            }

            if (undeletedSources.isNotEmpty()) {
                return MigrationResult(
                    true,
                    "הקבצים החדשים הותקנו ואומתו, והמיקום החדש פעיל. " +
                        "לא ניתן היה למחוק את קובצי המקור הישנים: " +
                        undeletedSources.joinToString()
                )
            }

            val undeletedBackups = mutableListOf<String>()

            for (asset in assets) {
                if (asset.backup.exists() && !deleteRecursively(asset.backup)) {
                    undeletedBackups += asset.backup.name
                }
                deleteRecursively(asset.temporary)
            }

            if (undeletedBackups.isNotEmpty()) {
                return MigrationResult(
                    true,
                    "ההעברה הושלמה והמקור נמחק. " +
                        "נותרו קובצי גיבוי זמניים במיקום החדש: " +
                        undeletedBackups.joinToString()
                )
            }

            MigrationResult(
                true,
                "ההעברה הושלמה. מאגר התחבורה, המפה וקובצי הניווט הועברו, " +
                    "אומתו והעותקים הישנים נמחקו."
            )
        } catch (exception: Exception) {
            failure(
                "העברת האחסון נכשלה: " +
                    (exception.message ?: "שגיאה לא ידועה")
            )
        }
    }

    private fun createAsset(
        source: File,
        target: File,
        isDirectory: Boolean,
        validator: (File) -> Unit
    ): Asset {
        return Asset(
            source = source,
            target = target,
            temporary = File(
                target.parentFile,
                "${target.name}.migration.tmp"
            ),
            backup = File(
                target.parentFile,
                "${target.name}.migration.previous"
            ),
            isDirectory = isDirectory,
            validate = validator
        )
    }

    private fun calculateSize(file: File): Long {
        if (!file.exists()) return 0L
        if (file.isFile) return file.length()
        return file.listFiles()?.sumOf { calculateSize(it) } ?: 0L
    }

    private fun deleteRecursively(file: File): Boolean {
        if (!file.exists()) return true
        var success = true
        if (file.isDirectory) {
            file.listFiles()?.forEach { child ->
                if (!deleteRecursively(child)) {
                    success = false
                }
            }
        }
        if (!file.delete()) {
            success = false
        }
        return success
    }

    private fun copyDirectory(source: File, destination: File) {
        check(source.isDirectory) { "תיקיית המקור אינה תקינה" }
        if (!destination.exists() && !destination.mkdirs()) {
            error("לא ניתן ליצור תיקיית יעד זמנית")
        }
        source.listFiles()?.forEach { child ->
            val targetChild = File(destination, child.name)
            if (child.isDirectory) {
                copyDirectory(child, targetChild)
            } else {
                copyFile(child, targetChild)
            }
        }
    }

    private fun copyFile(
        source: File,
        destination: File
    ) {
        FileInputStream(source).use { input ->
            FileOutputStream(destination).use { output ->
                input.copyTo(
                    output,
                    bufferSize = 1024 * 1024
                )
                output.fd.sync()
            }
        }
    }

    private fun validateDirectory(dir: File) {
        require(dir.isDirectory && !(dir.listFiles().isNullOrEmpty())) {
            "תיקיית נתוני ההליכה חסרה או ריקה"
        }
    }

    private fun validateAddressesDatabase(file: File) {
        require(file.isFile && file.length() > 100L) {
            "קובץ אינדקס הכתובות חסר או קטן מדי"
        }

        FileInputStream(file).use { input ->
            val header = ByteArray(16)
            require(input.read(header) == 16) {
                "לא ניתן לקרוא את כותרת קובץ הכתובות"
            }
            val expected = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
            require(header.contentEquals(expected)) {
                "קובץ הכתובות אינו מסד נתונים SQLite תקין"
            }
        }

        val database = SQLiteDatabase.openDatabase(
            file.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY
        )
        try {
            for (table in listOf("places", "streets", "addresses")) {
                database.rawQuery(
                    "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?",
                    arrayOf(table)
                ).use { cursor ->
                    require(cursor.moveToFirst()) {
                        "חסרה טבלה בקובץ הכתובות: $table"
                    }
                }
            }
        } finally {
            database.close()
        }
    }

    private fun validatePmtiles(file: File) {
        require(file.isFile && file.length() >= 127L) {
            "קובץ המפה חסר או קטן מדי"
        }

        FileInputStream(file).use { input ->
            val header = ByteArray(127)

            var offset = 0
            while (offset < header.size) {
                val count = input.read(
                    header,
                    offset,
                    header.size - offset
                )

                require(count > 0) {
                    "כותרת קובץ המפה אינה שלמה"
                }

                offset += count
            }

            val magic = "PMTiles".toByteArray(Charsets.US_ASCII)

            require(
                header.copyOfRange(0, 7).contentEquals(magic)
            ) {
                "הקובץ אינו ארכיון PMTiles"
            }

            require(header[7].toInt() and 0xFF == 3) {
                "גרסת PMTiles אינה נתמכת"
            }

            // The application uses vector tiles for its road and stop layers.
            require(header[100].toInt() and 0xFF == 1) {
                "המפה אינה מכילה אריחי Vector מסוג MVT"
            }
        }
    }

    private fun validateDatabase(file: File) {
        require(file.isFile && file.length() > 100L) {
            "קובץ המאגר חסר או קטן מדי"
        }

        FileInputStream(file).use { input ->
            val header = ByteArray(16)

            require(input.read(header) == 16) {
                "לא ניתן לקרוא את כותרת המאגר"
            }

            val expected = "SQLite format 3\u0000".toByteArray(
                Charsets.US_ASCII
            )

            require(header.contentEquals(expected)) {
                "הקובץ אינו מסד נתונים SQLite תקין"
            }
        }

        val database = SQLiteDatabase.openDatabase(
            file.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY
        )

        try {
            database.rawQuery(
                "PRAGMA integrity_check",
                null
            ).use { cursor ->
                require(
                    cursor.moveToFirst() &&
                        cursor.getString(0) == "ok"
                ) {
                    "בדיקת תקינות SQLite נכשלה"
                }
            }

            val requiredTables = listOf(
                "agency",
                "routes",
                "trips",
                "stops",
                "stop_times",
                "calendar",
                "route_patterns",
                "route_pattern_stops",
                "trip_patterns"
            )

            for (table in requiredTables) {
                database.rawQuery(
                    """
                    SELECT name
                    FROM sqlite_master
                    WHERE type = 'table' AND name = ?
                    """.trimIndent(),
                    arrayOf(table)
                ).use { cursor ->
                    require(cursor.moveToFirst()) {
                        "חסרה טבלה חיונית: $table"
                    }
                }
            }

            database.rawQuery(
                """
                SELECT
                    (SELECT COUNT(*) FROM trips),
                    (SELECT COUNT(*) FROM trip_patterns),
                    (
                        SELECT COUNT(*)
                        FROM trips t
                        LEFT JOIN trip_patterns tp
                            ON tp.trip_id = t.trip_id
                        WHERE tp.trip_id IS NULL
                    )
                """.trimIndent(),
                null
            ).use { cursor ->
                require(cursor.moveToFirst()) {
                    "לא ניתן לבדוק את שיוך הנסיעות"
                }

                require(
                    cursor.getLong(0) > 0L &&
                        cursor.getLong(0) == cursor.getLong(1) &&
                        cursor.getLong(2) == 0L
                ) {
                    "שיוך הנסיעות למסלולים אינו תקין"
                }
            }
        } finally {
            database.close()
        }
    }

    private fun failure(message: String) =
        MigrationResult(
            success = false,
            message = message
        )
}
