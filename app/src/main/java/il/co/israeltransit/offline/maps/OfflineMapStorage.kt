package il.co.israeltransit.offline.maps

import android.content.Context
import android.net.Uri
import il.co.israeltransit.offline.database.TransitStorage
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

class OfflineMapStorage(context: Context) {

    private val appContext = context.applicationContext
    private val transitStorage = TransitStorage(appContext)

    companion object {
        const val MAP_FILE_NAME = "israel-basemap.pmtiles"
        private const val TEMP_FILE_NAME = "$MAP_FILE_NAME.importing"
        private const val BACKUP_FILE_NAME = "$MAP_FILE_NAME.previous"
        private const val MINIMUM_HEADER_SIZE = 127L
    }

    data class ImportResult(
        val success: Boolean,
        val message: String
    )

    fun getMapFile(): File {
        return File(
            transitStorage.getSelectedDirectory(),
            MAP_FILE_NAME
        )
    }

    fun isMapAvailable(): Boolean {
        if (transitStorage.hasUnavailableSelectedLocation()) {
            return false
        }

        val file = getMapFile()

        return try {
            file.isFile && file.length() >= MINIMUM_HEADER_SIZE
        } catch (_: Exception) {
            false
        }
    }

    @Synchronized
    fun importArchive(uri: Uri): ImportResult {
        if (transitStorage.hasUnavailableSelectedLocation()) {
            return failure(
                "מיקום האחסון שנבחר אינו זמין. חבר מחדש את כרטיס הזיכרון."
            )
        }

        val directory = transitStorage.getSelectedDirectory()

        val target = File(directory, MAP_FILE_NAME)
        val temporary = File(directory, TEMP_FILE_NAME)
        val backup = File(directory, BACKUP_FILE_NAME)

        return try {
            if (!directory.exists() && !directory.mkdirs()) {
                error("לא ניתן ליצור את תיקיית המפות")
            }

            if (!directory.isDirectory || !directory.canWrite()) {
                error("אין הרשאת כתיבה למיקום האחסון שנבחר")
            }

            if (temporary.exists() || backup.exists()) {
                error(
                    "נמצאו קובצי ייבוא או גיבוי קודמים. " +
                        "לא נמחקו קבצים אלה באופן אוטומטי."
                )
            }

            val input = appContext.contentResolver.openInputStream(uri)
                ?: error("לא ניתן לפתוח את קובץ המפה שנבחר")

            input.use { source ->
                FileOutputStream(temporary).use { destination ->
                    source.copyTo(
                        destination,
                        bufferSize = 1024 * 1024
                    )
                    destination.fd.sync()
                }
            }

            validateArchive(temporary)

            if (target.exists()) {
                check(target.isFile) {
                    "קובץ המפה הקיים אינו קובץ רגיל"
                }

                validateArchive(target)

                check(target.renameTo(backup)) {
                    "לא ניתן לשמור זמנית את המפה הקיימת"
                }
            }

            try {
                check(temporary.renameTo(target)) {
                    "לא ניתן להתקין את המפה החדשה"
                }

                validateArchive(target)

                if (backup.exists() && !backup.delete()) {
                    return ImportResult(
                        true,
                        "המפה החדשה הותקנה ואומתה, " +
                            "אך לא ניתן היה למחוק את קובץ הגיבוי הישן."
                    )
                }
            } catch (exception: Exception) {
                target.delete()

                if (backup.exists()) {
                    backup.renameTo(target)
                }

                throw exception
            }

            ImportResult(
                true,
                "המפה הותקנה בהצלחה ואומתה. " +
                    "היא נשמרה במיקום האחסון שנבחר."
            )
        } catch (exception: Exception) {
            temporary.delete()

            if (!target.exists() && backup.exists()) {
                backup.renameTo(target)
            }

            failure(
                "ייבוא המפה נכשל: " +
                    (exception.message ?: "שגיאה לא ידועה")
            )
        }
    }

    private fun validateArchive(file: File) {
        require(file.isFile) {
            "קובץ המפה אינו קובץ רגיל"
        }

        require(file.length() >= MINIMUM_HEADER_SIZE) {
            "קובץ המפה קטן מדי או פגום"
        }

        FileInputStream(file).use { input ->
            val header = ByteArray(MINIMUM_HEADER_SIZE.toInt())

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

            val expectedMagic = "PMTiles".toByteArray(
                Charsets.US_ASCII
            )

            require(
                header.copyOfRange(0, 7)
                    .contentEquals(expectedMagic)
            ) {
                "הקובץ אינו ארכיון PMTiles"
            }

            require(
                (header[7].toInt() and 0xFF) == 3
            ) {
                "גרסת PMTiles אינה נתמכת"
            }
        }
    }

    private fun failure(message: String) =
        ImportResult(
            success = false,
            message = message
        )
}
