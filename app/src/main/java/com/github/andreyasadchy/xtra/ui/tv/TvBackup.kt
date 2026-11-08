package com.github.andreyasadchy.xtra.ui.tv

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Environment
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.os.LocaleListCompat
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.tv.material3.Text
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.util.C
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.system.exitProcess
import androidx.compose.ui.graphics.Color as ComposeColor

/**
 * TV: backup and restore of the settings and database (without the Twitch account), either to a file on the TV
 * (working even on TVs without a system file picker) or between the TV and a phone over Wi-Fi with a QR code.
 */
object TvBackup {

    private const val FILE_NAME = "XtraTV-backup.zip"
    private const val PREFS_ENTRY = "preferences.xml"
    private const val DB_ENTRY = "database"

    /** Builds the backup archive in memory (settings XML and Room database, never the Twitch account tokens). */
    fun buildBackupZip(context: Context): ByteArray {
        val app = context.applicationContext as XtraApp
        app.xtraModule.database.query(SimpleSQLiteQuery("PRAGMA wal_checkpoint(FULL)")).use {
            it.moveToPosition(-1)
        }
        val prefsFile = File("${context.applicationInfo.dataDir}/shared_prefs/${context.packageName}_preferences.xml")
        val dbFile = context.getDatabasePath("database")
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            if (prefsFile.exists()) {
                zip.putNextEntry(ZipEntry(PREFS_ENTRY))
                prefsFile.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
            if (dbFile.exists()) {
                zip.putNextEntry(ZipEntry(DB_ENTRY))
                dbFile.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /** Restores the settings XML and/or database from a backup zip (or raw XML / database bytes) and restarts the app. */
    fun applyBackupBytes(context: Context, bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        val prefsTarget = File("${context.applicationInfo.dataDir}/shared_prefs/${context.packageName}_preferences.xml")
        prefsTarget.parentFile?.mkdirs()
        val dbTarget = context.getDatabasePath("database")
        var restoredPrefs: String? = null
        var restoredDb = false
        // zip archive (starts with PK)
        if (bytes.size >= 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val name = entry.name.substringAfterLast('/')
                    when {
                        name == PREFS_ENTRY || name.endsWith("_preferences.xml") -> {
                            val data = zip.readBytes()
                            prefsTarget.writeBytes(data)
                            restoredPrefs = data.toString(Charsets.UTF_8)
                        }
                        name == DB_ENTRY -> {
                            File(dbTarget.parent, "database-shm").delete()
                            File(dbTarget.parent, "database-wal").delete()
                            dbTarget.writeBytes(zip.readBytes())
                            restoredDb = true
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        } else {
            val head = bytes.take(64).toByteArray().toString(Charsets.UTF_8)
            if (head.trimStart().startsWith("<?xml") || head.contains("<map")) {
                prefsTarget.writeBytes(bytes)
                restoredPrefs = bytes.toString(Charsets.UTF_8)
            } else if (head.startsWith("SQLite format 3")) {
                File(dbTarget.parent, "database-shm").delete()
                File(dbTarget.parent, "database-wal").delete()
                dbTarget.writeBytes(bytes)
                restoredDb = true
            }
        }
        if (restoredPrefs == null && !restoredDb) {
            return false
        }
        restoredPrefs?.let { prefs ->
            val language = Regex("<string name=\"${C.UI_LANGUAGE}\">(.+?)</string>").find(prefs)?.groups?.get(1)?.value
            CoroutineScope(Dispatchers.Main).launch {
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(language.takeIf { it != "auto" }))
            }
        }
        CoroutineScope(Dispatchers.Main).launch {
            context.startActivity(
                Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
            )
            exitProcess(0)
        }
        return true
    }

    /** Candidate folders on the TV where the backup file can be written or read without a system file picker. */
    private fun candidateFiles(context: Context): List<File> {
        val list = mutableListOf<File>()
        runCatching {
            val pub = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (pub != null) list += File(pub, FILE_NAME)
        }
        runCatching {
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.let { list += File(it, FILE_NAME) }
        }
        list += File(context.filesDir, FILE_NAME)
        return list.distinctBy { it.absolutePath }
    }

    /** Saves the backup directly to a file on the TV and shows its path. */
    fun saveToTv(context: Context, scope: CoroutineScope) {
        val appContext = context.applicationContext
        scope.launch {
            val saved = withContext(Dispatchers.IO) {
                val bytes = runCatching { buildBackupZip(appContext) }.getOrNull() ?: return@withContext null
                val prefsFile = File("${appContext.applicationInfo.dataDir}/shared_prefs/${appContext.packageName}_preferences.xml")
                val dbFile = appContext.getDatabasePath("database")
                for (target in candidateFiles(appContext)) {
                    val ok = runCatching {
                        target.parentFile?.mkdirs()
                        target.writeBytes(bytes)
                        // also write the raw files next to the archive for users who copy them by hand
                        val dir = target.parentFile
                        if (dir != null) {
                            if (prefsFile.exists()) prefsFile.copyTo(File(dir, prefsFile.name), overwrite = true)
                            if (dbFile.exists()) dbFile.copyTo(File(dir, dbFile.name), overwrite = true)
                        }
                        true
                    }.getOrDefault(false)
                    if (ok) return@withContext target
                }
                null
            }
            if (saved != null) {
                Toast.makeText(appContext, appContext.getString(R.string.tv_backup_saved, saved.absolutePath), Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Restores from a backup file found on the TV, or returns false if none is there yet. */
    fun restoreFromTv(context: Context, scope: CoroutineScope, onNotFound: () -> Unit) {
        val appContext = context.applicationContext
        scope.launch {
            val found = withContext(Dispatchers.IO) {
                for (candidate in candidateFiles(appContext)) {
                    if (candidate.exists() && candidate.length() > 0) {
                        return@withContext candidate.readBytes()
                    }
                    val dir = candidate.parentFile ?: continue
                    val prefsFile = File(dir, "${appContext.packageName}_preferences.xml")
                    val dbFile = File(dir, DB_ENTRY)
                    if (prefsFile.exists() || dbFile.exists()) {
                        val out = ByteArrayOutputStream()
                        ZipOutputStream(out).use { zip ->
                            if (prefsFile.exists()) {
                                zip.putNextEntry(ZipEntry(PREFS_ENTRY))
                                prefsFile.inputStream().use { it.copyTo(zip) }
                                zip.closeEntry()
                            }
                            if (dbFile.exists()) {
                                zip.putNextEntry(ZipEntry(DB_ENTRY))
                                dbFile.inputStream().use { it.copyTo(zip) }
                                zip.closeEntry()
                            }
                        }
                        return@withContext out.toByteArray()
                    }
                }
                null
            }
            if (found != null) {
                Toast.makeText(appContext, R.string.tv_backup_restored, Toast.LENGTH_SHORT).show()
                withContext(Dispatchers.IO) { applyBackupBytes(appContext, found) }
            } else {
                onNotFound()
            }
        }
    }

    /** Shows a QR code that serves the backup file over local Wi-Fi to a phone. */
    fun showQrExport(context: Context, scope: CoroutineScope) {
        val appContext = context.applicationContext
        scope.launch {
            val ip = withContext(Dispatchers.IO) { findLocalIpAddress() }
            if (ip == null) {
                Toast.makeText(appContext, R.string.tv_backup_no_network, Toast.LENGTH_LONG).show()
                return@launch
            }
            val zipBytes = withContext(Dispatchers.IO) { runCatching { buildBackupZip(appContext) }.getOrNull() } ?: return@launch
            val server = withContext(Dispatchers.IO) { runCatching { ServerSocket(0) }.getOrNull() } ?: return@launch
            val url = "http://$ip:${server.localPort}/"
            val qrBitmap = withContext(Dispatchers.Default) { QrCode.encode(url, 520) }
            val dialog = TvDialogs.show(context) { dismiss ->
                SidePanel(title = context.getString(R.string.tv_backup_qr_send)) {
                    val closeRequester = remember { FocusRequester() }
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            context.getString(R.string.tv_backup_qr_send_help),
                            fontSize = 15.sp,
                            color = TvColors.secondaryText,
                        )
                        Spacer(Modifier.height(18.dp))
                        Box(
                            Modifier
                                .size(220.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(ComposeColor.White)
                                .padding(10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Image(
                                bitmap = qrBitmap.asImageBitmap(),
                                contentDescription = url,
                                modifier = Modifier.size(200.dp)
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(url, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = ComposeColor.White)
                        Spacer(Modifier.height(18.dp))
                        ButtonRow(
                            label = context.getString(R.string.close_player),
                            modifier = Modifier.focusRequester(closeRequester),
                            onClick = dismiss,
                        )
                    }
                    LaunchedEffect(Unit) {
                        runCatching { closeRequester.requestFocus() }
                    }
                }
            }
            val serverJob = scope.launch(Dispatchers.IO) {
                while (!server.isClosed) {
                    val client = runCatching { server.accept() }.getOrNull() ?: break
                    launch(Dispatchers.IO) { handleExportClient(client, zipBytes, appContext) }
                }
            }
            dialog.setOnDismissListener {
                serverJob.cancel()
                runCatching { server.close() }
            }
        }
    }

    /** Shows a QR code that lets a phone upload a backup file over local Wi-Fi to restore on the TV. */
    fun showQrImport(context: Context, scope: CoroutineScope) {
        val appContext = context.applicationContext
        scope.launch {
            val ip = withContext(Dispatchers.IO) { findLocalIpAddress() }
            if (ip == null) {
                Toast.makeText(appContext, R.string.tv_backup_no_network, Toast.LENGTH_LONG).show()
                return@launch
            }
            val server = withContext(Dispatchers.IO) { runCatching { ServerSocket(0) }.getOrNull() } ?: return@launch
            val url = "http://$ip:${server.localPort}/"
            val qrBitmap = withContext(Dispatchers.Default) { QrCode.encode(url, 520) }
            var dialogRef: androidx.activity.ComponentDialog? = null
            val dialog = TvDialogs.show(context) { dismiss ->
                SidePanel(title = context.getString(R.string.tv_backup_qr_receive)) {
                    val closeRequester = remember { FocusRequester() }
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            context.getString(R.string.tv_backup_qr_receive_help),
                            fontSize = 15.sp,
                            color = TvColors.secondaryText,
                        )
                        Spacer(Modifier.height(18.dp))
                        Box(
                            Modifier
                                .size(220.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(ComposeColor.White)
                                .padding(10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Image(
                                bitmap = qrBitmap.asImageBitmap(),
                                contentDescription = url,
                                modifier = Modifier.size(200.dp)
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(url, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = ComposeColor.White)
                        Spacer(Modifier.height(18.dp))
                        ButtonRow(
                            label = context.getString(R.string.close_player),
                            modifier = Modifier.focusRequester(closeRequester),
                            onClick = dismiss,
                        )
                    }
                    LaunchedEffect(Unit) {
                        runCatching { closeRequester.requestFocus() }
                    }
                }
            }
            dialogRef = dialog
            val serverJob = scope.launch(Dispatchers.IO) {
                while (!server.isClosed) {
                    val client = runCatching { server.accept() }.getOrNull() ?: break
                    launch(Dispatchers.IO) {
                        val uploaded = handleImportClient(client, appContext)
                        if (uploaded != null) {
                            withContext(Dispatchers.Main) {
                                Toast.makeText(appContext, R.string.tv_backup_restored, Toast.LENGTH_SHORT).show()
                                runCatching { dialogRef?.dismiss() }
                            }
                            applyBackupBytes(appContext, uploaded)
                        }
                    }
                }
            }
            dialog.setOnDismissListener {
                serverJob.cancel()
                runCatching { server.close() }
            }
        }
    }

    private fun handleExportClient(socket: Socket, zipBytes: ByteArray, context: Context) {
        socket.use { s ->
            s.soTimeout = 10_000
            val input = s.getInputStream()
            val requestLine = readLine(input) ?: return
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
            }
            val out = s.getOutputStream()
            if (requestLine.startsWith("GET /backup")) {
                val header = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: application/zip\r\n" +
                        "Content-Disposition: attachment; filename=\"$FILE_NAME\"\r\n" +
                        "Content-Length: ${zipBytes.size}\r\n" +
                        "Connection: close\r\n\r\n"
                out.write(header.toByteArray(Charsets.UTF_8))
                out.write(zipBytes)
            } else {
                val title = context.getString(R.string.tv_backup_qr_send)
                val body = """
                    <!DOCTYPE html><html><head><meta charset="utf-8">
                    <meta name="viewport" content="width=device-width,initial-scale=1">
                    <title>Xtra TV</title>
                    <style>body{background:#121216;color:#fff;font-family:sans-serif;text-align:center;padding:40px 20px}
                    a.btn{display:inline-block;margin-top:24px;padding:14px 28px;background:#9147ff;color:#fff;text-decoration:none;border-radius:10px;font-weight:bold;font-size:17px}</style>
                    </head><body><h2>Xtra TV</h2><p>$title</p>
                    <a class="btn" href="/backup">$FILE_NAME</a>
                    <script>setTimeout(function(){location.href='/backup'},300);</script>
                    </body></html>
                """.trimIndent().toByteArray(Charsets.UTF_8)
                val header = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                out.write(header.toByteArray(Charsets.UTF_8))
                out.write(body)
            }
            out.flush()
        }
    }

    private fun handleImportClient(socket: Socket, context: Context): ByteArray? {
        socket.use { s ->
            s.soTimeout = 30_000
            val input = s.getInputStream()
            val requestLine = readLine(input) ?: return null
            var contentLength = 0
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                if (line.startsWith("Content-Length:", ignoreCase = true)) {
                    contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
                }
            }
            val out = s.getOutputStream()
            if (requestLine.startsWith("POST /upload") && contentLength in 1..(20 * 1024 * 1024)) {
                val body = ByteArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val n = input.read(body, read, contentLength - read)
                    if (n <= 0) break
                    read += n
                }
                val doneText = context.getString(R.string.tv_backup_restored).toByteArray(Charsets.UTF_8)
                val header = "HTTP/1.1 200 OK\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: ${doneText.size}\r\nConnection: close\r\n\r\n"
                out.write(header.toByteArray(Charsets.UTF_8))
                out.write(doneText)
                out.flush()
                return body.takeIf { read == contentLength }
            } else {
                val title = context.getString(R.string.tv_backup_qr_receive)
                val doneMsg = context.getString(R.string.tv_backup_restored)
                val html = """
                    <!DOCTYPE html><html><head><meta charset="utf-8">
                    <meta name="viewport" content="width=device-width,initial-scale=1">
                    <title>Xtra TV</title>
                    <style>body{background:#121216;color:#fff;font-family:sans-serif;text-align:center;padding:40px 20px}
                    input[type=file]{display:block;margin:24px auto;color:#fff;font-size:16px}
                    button{padding:14px 28px;background:#9147ff;color:#fff;border:0;border-radius:10px;font-weight:bold;font-size:17px}</style>
                    </head><body><h2>Xtra TV</h2><p>$title</p>
                    <input type="file" id="f" accept=".zip,.xml,*/*">
                    <button onclick="send()">OK</button><p id="s"></p>
                    <script>
                    function send(){
                      var f=document.getElementById('f').files[0];if(!f)return;
                      fetch('/upload',{method:'POST',body:f}).then(function(){document.getElementById('s').innerText='$doneMsg';});
                    }
                    </script></body></html>
                """.trimIndent().toByteArray(Charsets.UTF_8)
                val header = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${html.size}\r\nConnection: close\r\n\r\n"
                out.write(header.toByteArray(Charsets.UTF_8))
                out.write(html)
                out.flush()
                return null
            }
        }
    }

    private fun readLine(input: java.io.InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) {
                if (sb.isNotEmpty() && sb.last() == '\r') sb.setLength(sb.length - 1)
                return sb.toString()
            }
            sb.append(b.toChar())
        }
    }

    private fun findLocalIpAddress(): String? {
        return runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress && it.isSiteLocalAddress }
                ?.hostAddress
        }.getOrNull()
    }
}

/** Compact ISO/IEC 18004 QR code generator (Byte mode, Version 3, ECC Level L — up to 53 bytes). */
internal object QrCode {

    fun encode(text: String, sizePx: Int): Bitmap {
        val data = text.toByteArray(Charsets.ISO_8859_1)
        // Version 3 (29x29), ECC Level L: 55 data codewords, 15 EC codewords (total 70)
        val version = 3
        val dim = 17 + 4 * version // 29
        val totalDataCodewords = 55
        val ecCodewords = 15

        val bits = ArrayList<Int>(totalDataCodewords * 8)
        fun pushBits(value: Int, count: Int) {
            for (i in count - 1 downTo 0) bits.add((value ushr i) and 1)
        }
        // Byte mode (0100) + 8-bit length
        pushBits(0b0100, 4)
        pushBits(data.size, 8)
        for (b in data) pushBits(b.toInt() and 0xFF, 8)
        repeat(minOf(4, totalDataCodewords * 8 - bits.size)) { bits.add(0) }
        while (bits.size % 8 != 0) bits.add(0)
        val padBytes = intArrayOf(0xEC, 0x11)
        var padIdx = 0
        while (bits.size < totalDataCodewords * 8) {
            pushBits(padBytes[padIdx % 2], 8)
            padIdx++
        }

        val codewords = IntArray(totalDataCodewords + ecCodewords)
        for (i in 0 until totalDataCodewords) {
            var v = 0
            for (b in 0 until 8) v = (v shl 1) or bits[i * 8 + b]
            codewords[i] = v
        }
        val ec = reedSolomon(codewords.copyOfRange(0, totalDataCodewords), ecCodewords)
        System.arraycopy(ec, 0, codewords, totalDataCodewords, ecCodewords)

        val modules = Array(dim) { IntArray(dim) }
        val isFunction = Array(dim) { BooleanArray(dim) }

        fun setFunction(r: Int, c: Int, dark: Boolean) {
            if (r in 0 until dim && c in 0 until dim) {
                modules[r][c] = if (dark) 1 else 0
                isFunction[r][c] = true
            }
        }

        fun drawFinder(row: Int, col: Int) {
            for (dr in -1..7) {
                for (dc in -1..7) {
                    val r = row + dr
                    val c = col + dc
                    val dark = (dr in 0..6 && (dc == 0 || dc == 6)) ||
                            (dc in 0..6 && (dr == 0 || dr == 6)) ||
                            (dr in 2..4 && dc in 2..4)
                    setFunction(r, c, dark)
                }
            }
        }
        drawFinder(0, 0)
        drawFinder(0, dim - 7)
        drawFinder(dim - 7, 0)

        // Timing patterns
        for (i in 8 until dim - 8) {
            setFunction(6, i, i % 2 == 0)
            setFunction(i, 6, i % 2 == 0)
        }

        // Alignment pattern for Version 3 at (22, 22)
        val alignCenter = 22
        for (dr in -2..2) {
            for (dc in -2..2) {
                val dark = kotlin.math.abs(dr) == 2 || kotlin.math.abs(dc) == 2 || (dr == 0 && dc == 0)
                setFunction(alignCenter + dr, alignCenter + dc, dark)
            }
        }

        // Reserve format info areas & dark module
        for (i in 0..8) {
            if (!isFunction[8][i]) setFunction(8, i, false)
            if (!isFunction[i][8]) setFunction(i, 8, false)
        }
        for (i in 0..7) {
            if (!isFunction[8][dim - 1 - i]) setFunction(8, dim - 1 - i, false)
            if (!isFunction[dim - 1 - i][8]) setFunction(dim - 1 - i, 8, false)
        }
        setFunction(dim - 8, 8, true)

        // Place data bits in zigzag
        var bitIndex = 0
        val totalBits = codewords.size * 8
        var col = dim - 1
        var upward = true
        while (col > 0) {
            if (col == 6) col--
            for (step in 0 until dim) {
                val row = if (upward) dim - 1 - step else step
                for (cOffset in 0..1) {
                    val c = col - cOffset
                    if (!isFunction[row][c]) {
                        val dark = if (bitIndex < totalBits) {
                            ((codewords[bitIndex / 8] ushr (7 - (bitIndex % 8))) and 1) == 1
                        } else false
                        // Mask 0: (row + c) % 2 == 0
                        val masked = if ((row + c) % 2 == 0) !dark else dark
                        modules[row][c] = if (masked) 1 else 0
                        bitIndex++
                    }
                }
            }
            upward = !upward
            col -= 2
        }

        // Format information for ECC Level L (01) and Mask 0 (000) -> 0x77C4
        val formatBits = 0x77C4
        for (i in 0..5) setFunction(8, i, ((formatBits ushr (14 - i)) and 1) == 1)
        setFunction(8, 7, ((formatBits ushr 8) and 1) == 1)
        setFunction(8, 8, ((formatBits ushr 7) and 1) == 1)
        setFunction(7, 8, ((formatBits ushr 6) and 1) == 1)
        for (i in 0..5) setFunction(5 - i, 8, ((formatBits ushr i) and 1) == 1)
        for (i in 0..7) setFunction(8, dim - 8 + i, ((formatBits ushr (7 - i)) and 1) == 1)
        for (i in 0..6) setFunction(dim - 1 - i, 8, ((formatBits ushr (14 - i)) and 1) == 1)

        val quiet = 2
        val totalModules = dim + quiet * 2
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        for (y in 0 until sizePx) {
            val mr = (y * totalModules) / sizePx - quiet
            for (x in 0 until sizePx) {
                val mc = (x * totalModules) / sizePx - quiet
                val dark = mr in 0 until dim && mc in 0 until dim && modules[mr][mc] == 1
                bmp.setPixel(x, y, if (dark) Color.BLACK else Color.WHITE)
            }
        }
        return bmp
    }

    private fun reedSolomon(data: IntArray, ecLen: Int): IntArray {
        val exp = IntArray(512)
        val log = IntArray(256)
        var x = 1
        for (i in 0 until 255) {
            exp[i] = x
            log[x] = i
            x = x shl 1
            if (x and 0x100 != 0) x = x xor 0x11D
        }
        for (i in 255 until 512) exp[i] = exp[i - 255]
        fun mul(a: Int, b: Int): Int = if (a == 0 || b == 0) 0 else exp[log[a] + log[b]]

        var gen = intArrayOf(1)
        for (i in 0 until ecLen) {
            val next = IntArray(gen.size + 1)
            val root = exp[i]
            for (j in gen.indices) {
                next[j] = next[j] xor gen[j]
                next[j + 1] = next[j + 1] xor mul(gen[j], root)
            }
            gen = next
        }
        val rem = IntArray(ecLen)
        for (b in data) {
            val factor = b xor rem[0]
            System.arraycopy(rem, 1, rem, 0, ecLen - 1)
            rem[ecLen - 1] = 0
            for (i in 0 until ecLen) {
                rem[i] = rem[i] xor mul(gen[i + 1], factor)
            }
        }
        return rem
    }
}
