package il.co.israeltransit.offline.maps

import android.content.Context
import android.net.Uri
import il.co.israeltransit.offline.database.TransitStorage
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

class OfflineMapStorage(context: Context) {

    private val appContext = context.applicationContext
    private val transitStorage = TransitStorage(appContext)

    companion object {
        const val MAP_FILE_NAME = "israel-basemap.pmtiles"
        const val MAP_PACK_FILE_NAME = "israel-map-pack.zip"
        const val ADDRESSES_DB_NAME = "israel-addresses.sqlite"
        const val GRAPHHOPPER_DIR_NAME = "graphhopper-foot"

        private const val TEMP_FILE_NAME = "$MAP_FILE_NAME.importing"
        private const val BACKUP_FILE_NAME = "$MAP_FILE_NAME.previous"
        private const val PACK_DOWNLOAD_TEMP = "israel-map-pack.download.tmp"
        private const val PACK_STAGING_DIR = "israel-map-pack.staging.tmp"
        private const val ADDRESSES_BACKUP_NAME = "$ADDRESSES_DB_NAME.previous"
        private const val GRAPHHOPPER_BACKUP_NAME = "$GRAPHHOPPER_DIR_NAME.previous"

        private const val MINIMUM_HEADER_SIZE = 127L
        private const val BUFFER_SIZE = 64 * 1024
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

    fun getAddressesFile(): File {
        return File(
            transitStorage.getSelectedDirectory(),
            ADDRESSES_DB_NAME
        )
    }

    fun getGraphHopperDirectory(): File {
        return File(
            transitStorage.getSelectedDirectory(),
            GRAPHHOPPER_DIR_NAME
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

    fun isAddressesAvailable(): Boolean {
        if (transitStorage.hasUnavailableSelectedLocation()) {
            return false
        }

        val file = getAddressesFile()

        return try {
            file.isFile && file.length() > 100L
        } catch (_: Exception) {
            false
        }
    }

    fun isWalkingGraphAvailable(): Boolean {
        if (transitStorage.hasUnavailableSelectedLocation()) {
            return false
        }

        val dir = getGraphHopperDirectory()

        return try {
            dir.isDirectory && !(dir.listFiles().isNullOrEmpty())
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
        val downloadTemp = File(directory, PACK_DOWNLOAD_TEMP)

        return try {
            if (!directory.exists() && !directory.mkdirs()) {
                error("לא ניתן ליצור את תיקיית המפות")
            }

            if (!directory.isDirectory || !directory.canWrite()) {
                error("אין הרשאת כתיבה למיקום האחסון שנבחר")
            }

            downloadTemp.delete()

            val input = appContext.contentResolver.openInputStream(uri)
                ?: error("לא ניתן לפתוח את הקובץ שנבחר")

            input.use { source ->
                BufferedOutputStream(FileOutputStream(downloadTemp), BUFFER_SIZE).use { destination ->
                    source.copyTo(destination, bufferSize = BUFFER_SIZE)
                    destination.flush()
                }
            }

            require(downloadTemp.isFile && downloadTemp.length() >= 4L) {
                "הקובץ שנבחר ריק או פגום"
            }

            val magic = ByteArray(7)
            val magicRead = FileInputStream(downloadTemp).use { it.read(magic) }

            val isZip = magicRead >= 4 &&
                magic[0] == 0x50.toByte() &&
                magic[1] == 0x4B.toByte() &&
                (magic[2] == 0x03.toByte() || magic[2] == 0x05.toByte())

            val result = if (isZip) {
                importZipMapPack(directory, downloadTemp)
            } else {
                importSinglePmtiles(directory, downloadTemp)
            }

            downloadTemp.delete()
            result
        } catch (exception: Exception) {
            downloadTemp.delete()
            failure(
                "ייבוא המפה נכשל: " +
                    (exception.message ?: "שגיאה לא ידועה")
            )
        }
    }

    private fun importZipMapPack(
        directory: File,
        zipFile: File
    ): ImportResult {
        val stagingDir = File(directory, PACK_STAGING_DIR)
        val mapTarget = File(directory, MAP_FILE_NAME)
        val mapBackup = File(directory, BACKUP_FILE_NAME)
        val addrTarget = File(directory, ADDRESSES_DB_NAME)
        val addrBackup = File(directory, ADDRESSES_BACKUP_NAME)
        val ghTarget = File(directory, GRAPHHOPPER_DIR_NAME)
        val ghBackup = File(directory, GRAPHHOPPER_BACKUP_NAME)

        deleteRecursively(stagingDir)
        check(stagingDir.mkdirs()) { "לא ניתן ליצור תיקיית פריסה זמנית" }

        try {
            val stagingCanonical = stagingDir.canonicalPath + File.separator

            ZipInputStream(BufferedInputStream(FileInputStream(zipFile), BUFFER_SIZE)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val cleanName = entry.name
                        .replace('\\', '/')
                        .removePrefix("./")
                        .trimStart('/')

                    if (cleanName.isNotEmpty()) {
                        val outFile = File(stagingDir, cleanName)
                        val outCanonical = outFile.canonicalPath

                        require(outCanonical.startsWith(stagingCanonical) || outCanonical == stagingDir.canonicalPath) {
                            "נמצא נתיב לא תקין בארכיון המפה"
                        }

                        if (entry.isDirectory) {
                            outFile.mkdirs()
                        } else {
                            outFile.parentFile?.mkdirs()
                            FileOutputStream(outFile).use { fos ->
                                val buf = ByteArray(BUFFER_SIZE)
                                var len: Int
                                while (zis.read(buf).also { len = it } != -1) {
                                    fos.write(buf, 0, len)
                                }
                                fos.flush()
                                fos.fd.sync()
                            }
                        }
                    }

                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }

            val stagedMap = File(stagingDir, MAP_FILE_NAME)
            require(stagedMap.isFile) {
                "חבילת המפה אינה מכילה את קובץ המפה ($MAP_FILE_NAME)"
            }
            validateArchive(stagedMap)

            val stagedAddresses = File(stagingDir, ADDRESSES_DB_NAME)
            val hasAddresses = stagedAddresses.isFile
            if (hasAddresses) {
                validateSqliteHeader(stagedAddresses)
            }

            val stagedGh = File(stagingDir, GRAPHHOPPER_DIR_NAME)
            val hasGh = stagedGh.isDirectory && !(stagedGh.listFiles().isNullOrEmpty())

            deleteRecursively(mapBackup)
            deleteRecursively(addrBackup)
            deleteRecursively(ghBackup)

            var mapBackedUp = false
            var addrBackedUp = false
            var ghBackedUp = false

            try {
                if (mapTarget.exists()) {
                    check(mapTarget.renameTo(mapBackup)) { "לא ניתן לגבות את המפה הקיימת" }
                    mapBackedUp = true
                }
                if (hasAddresses && addrTarget.exists()) {
                    check(addrTarget.renameTo(addrBackup)) { "לא ניתן לגבות את אינדקס הכתובות הקיים" }
                    addrBackedUp = true
                }
                if (hasGh && ghTarget.exists()) {
                    check(ghTarget.renameTo(ghBackup)) { "לא ניתן לגבות את נתוני ההליכה הקיימים" }
                    ghBackedUp = true
                }

                check(stagedMap.renameTo(mapTarget)) { "לא ניתן להתקין את המפה החדשה" }
                validateArchive(mapTarget)

                if (hasAddresses) {
                    check(stagedAddresses.renameTo(addrTarget)) { "לא ניתן להתקין את אינדקס הכתובות" }
                    validateSqliteHeader(addrTarget)
                }

                if (hasGh) {
                    check(stagedGh.renameTo(ghTarget)) { "לא ניתן להתקין את נתוני מנוע ההליכה" }
                }

                deleteRecursively(mapBackup)
                deleteRecursively(addrBackup)
                deleteRecursively(ghBackup)
                deleteRecursively(stagingDir)

                val message = when {
                    hasAddresses && hasGh ->
                        "חבילת המפה הותקנה בהצלחה! כולל מפה, רשת הליכה וחיפוש רחובות וכתובות."
                    hasAddresses || hasGh ->
                        "חבילת המפה הותקנה ואומתה בהצלחה."
                    else ->
                        "המפה הותקנה בהצלחה ואומתה."
                }
                return ImportResult(true, message)
            } catch (installException: Exception) {
                if (mapTarget.exists()) deleteRecursively(mapTarget)
                if (mapBackedUp && mapBackup.exists()) mapBackup.renameTo(mapTarget)

                if (hasAddresses) {
                    if (addrTarget.exists()) deleteRecursively(addrTarget)
                    if (addrBackedUp && addrBackup.exists()) addrBackup.renameTo(addrTarget)
                }

                if (hasGh) {
                    if (ghTarget.exists()) deleteRecursively(ghTarget)
                    if (ghBackedUp && ghBackup.exists()) ghBackup.renameTo(ghTarget)
                }

                throw installException
            }
        } finally {
            deleteRecursively(stagingDir)
        }
    }

    private fun importSinglePmtiles(
        directory: File,
        downloadedFile: File
    ): ImportResult {
        val target = File(directory, MAP_FILE_NAME)
        val temporary = File(directory, TEMP_FILE_NAME)
        val backup = File(directory, BACKUP_FILE_NAME)

        deleteRecursively(temporary)
        check(downloadedFile.renameTo(temporary)) {
            "לא ניתן להכין את קובץ המפה להתקנה"
        }

        return try {
            validateArchive(temporary)

            if (target.exists()) {
                check(target.isFile) {
                    "קובץ המפה הקיים אינו קובץ רגיל"
                }

                validateArchive(target)

                deleteRecursively(backup)
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

            throw exception
        }
    }

    private fun validateSqliteHeader(file: File) {
        require(file.isFile && file.length() > 100L) {
            "קובץ אינדקס הכתובות ריק או פגום"
        }

        FileInputStream(file).use { input ->
            val header = ByteArray(16)
            require(input.read(header) == 16) {
                "לא ניתן לקרוא את כותרת אינדקס הכתובות"
            }
            val expected = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
            require(header.contentEquals(expected)) {
                "קובץ הכתובות אינו SQLite תקין"
            }
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

    private fun deleteRecursively(file: File) {
        if (!file.exists()) return
        if (file.isDirectory) {
            file.listFiles()?.forEach { child ->
                deleteRecursively(child)
            }
        }
        file.delete()
    }

    private fun failure(message: String) =
        ImportResult(
            success = false,
            message = message
        )
}
