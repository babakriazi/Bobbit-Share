package com.babakriazi.bobbitshare

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class FileServer(
    private val context: Context,
    private val files: List<FileItem>,
    port: Int = 8080
) : NanoHTTPD(port) {

    private val activeConnections = AtomicInteger(0)
    private val receivedFiles = CopyOnWriteArrayList<String>()

    var onClientConnected: ((Int) -> Unit)? = null
    var onFileReceived: ((String) -> Unit)? = null

    fun getReceivedFiles(): List<String> = receivedFiles.toList()

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method

        activeConnections.incrementAndGet()
        onClientConnected?.invoke(activeConnections.get())

        return try {
            when {
                method == Method.POST && (uri == "/upload" || uri.startsWith("/upload")) -> handleUpload(session)
                uri == "/" || uri == "/index.html" -> serveIndex()
                uri.startsWith("/download/") -> serveFile(uri.removePrefix("/download/"))
                uri == "/status" -> newFixedLengthResponse(
                    Response.Status.OK,
                    "application/json",
                    "{\"files\":${files.size},\"clients\":${activeConnections.get()},\"received\":${receivedFiles.size}}"
                )
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
            }
        } catch (e: Exception) {
            Log.e(TAG, "serve error", e)
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Error: ${e.message}")
        }
    }

    private fun handleUpload(session: IHTTPSession): Response {
        return try {
            val filesMap = HashMap<String, String>()
            session.parseBody(filesMap)
            val parms = session.parms
            var savedName: String? = null

            if (filesMap.isNotEmpty()) {
                for ((key, tempPath) in filesMap) {
                    if (tempPath.isNullOrBlank()) continue
                    val tempFile = File(tempPath)
                    if (!tempFile.exists()) continue

                    var name = parms[key] ?: parms["file"] ?: tempFile.name
                    if (name.isBlank() || name == tempPath) {
                        name = "upload_${System.currentTimeMillis()}"
                    }
                    name = name.replace(Regex("[\\\\/]+"), "_").trim()
                    if (name.isBlank()) name = "upload_${System.currentTimeMillis()}"

                    saveUploadedFile(tempFile, name)
                    savedName = name
                    receivedFiles.add(name)
                    onFileReceived?.invoke(name)
                    try { tempFile.delete() } catch (_: Exception) {}
                }
            }

            if (savedName == null) {
                return newFixedLengthResponse(
                    Response.Status.BAD_REQUEST,
                    "text/html; charset=utf-8",
                    redirectHtml("فایلی دریافت نشد", false)
                )
            }

            newFixedLengthResponse(
                Response.Status.OK,
                "text/html; charset=utf-8",
                redirectHtml("فایل «$savedName» با موفقیت روی گوشی ذخیره شد", true)
            )
        } catch (e: Exception) {
            Log.e(TAG, "upload failed", e)
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "text/html; charset=utf-8",
                redirectHtml("خطا در آپلود: ${e.message}", false)
            )
        }
    }

    private fun saveUploadedFile(tempFile: File, name: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, guessMime(name))
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val itemUri = resolver.insert(collection, values)
                ?: throw IllegalStateException("Cannot create MediaStore entry")

            resolver.openOutputStream(itemUri)?.use { out ->
                FileInputStream(tempFile).use { input -> input.copyTo(out) }
            } ?: throw IllegalStateException("Cannot open output stream")

            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(itemUri, values, null, null)
        } else {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!dir.exists()) dir.mkdirs()
            val dest = File(dir, name)
            FileInputStream(tempFile).use { input ->
                FileOutputStream(dest).use { out -> input.copyTo(out) }
            }
        }
        Log.d(TAG, "Saved upload: $name")
    }

    private fun guessMime(name: String): String {
        val lower = name.lowercase()
        return when {
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
            lower.endsWith(".png") -> "image/png"
            lower.endsWith(".gif") -> "image/gif"
            lower.endsWith(".webp") -> "image/webp"
            lower.endsWith(".mp4") -> "video/mp4"
            lower.endsWith(".pdf") -> "application/pdf"
            lower.endsWith(".txt") -> "text/plain"
            lower.endsWith(".zip") -> "application/zip"
            lower.endsWith(".apk") -> "application/vnd.android.package-archive"
            else -> "application/octet-stream"
        }
    }

    private fun redirectHtml(message: String, ok: Boolean): String {
        val color = if (ok) "#22C55E" else "#EF4444"
        return """
            <!DOCTYPE html><html lang="fa" dir="rtl"><head>
            <meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
            <meta http-equiv="refresh" content="2;url=/">
            <title>Bobbit Share</title>
            <style>
              body{font-family:system-ui,sans-serif;background:#0B1120;color:#F1F5F9;
              display:flex;align-items:center;justify-content:center;min-height:100vh;margin:0;padding:20px;text-align:center}
              .box{background:#1E293B;border-radius:16px;padding:28px 24px;max-width:400px}
              h2{color:$color;margin:0 0 12px}
              a{color:#38BDF8}
            </style></head><body>
            <div class="box"><h2>$message</h2><p><a href="/">بازگشت</a></p></div>
            </body></html>
        """.trimIndent()
    }

    private fun serveIndex(): Response {
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", buildHtmlPage())
    }

    private fun serveFile(encodedName: String): Response {
        val name = URLDecoder.decode(encodedName, "UTF-8")
        val file = files.find { it.name == name }
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "File not found")

        return try {
            val inputStream: InputStream = context.contentResolver.openInputStream(file.uri)
                ?: return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Cannot open file")

            val response = newFixedLengthResponse(
                Response.Status.OK,
                file.mimeType ?: "application/octet-stream",
                inputStream,
                if (file.size > 0) file.size else -1L
            )
            response.addHeader(
                "Content-Disposition",
                "attachment; filename=\"${file.name.replace("\"", "")}\""
            )
            response
        } catch (e: Exception) {
            Log.e(TAG, "serveFile", e)
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, e.message)
        }
    }

    private fun buildHtmlPage(): String {
        val fileRows = if (files.isEmpty()) {
            """<div class="empty">فایلی از گوشی برای دانلود نیست — می‌توانید از پایین فایل بفرستید</div>"""
        } else {
            files.joinToString("\n") { f ->
                val enc = URLEncoder.encode(f.name, "UTF-8")
                """
                <div class="file-card">
                  <div class="file-info">
                    <div class="file-name">${escapeHtml(f.name)}</div>
                    <div class="file-size">${escapeHtml(f.formattedSize())}</div>
                  </div>
                  <a class="btn" href="/download/$enc">دانلود</a>
                </div>
                """.trimIndent()
            }
        }

        val receivedNote = if (receivedFiles.isNotEmpty()) {
            """<div class="received">آخرین دریافت‌ها روی گوشی: ${escapeHtml(receivedFiles.takeLast(5).joinToString("، "))}</div>"""
        } else ""

        return """
<!DOCTYPE html>
<html lang="fa" dir="rtl">
<head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1">
    <title>Bobbit Share</title>
    <style>
        :root {
            --bg: #0B1120; --surface: #1E293B; --primary: #6366F1;
            --primary-light: #818CF8; --secondary: #22D3EE;
            --text: #F1F5F9; --text2: #94A3B8; --ok: #22C55E;
        }
        * { box-sizing: border-box; margin: 0; padding: 0; }
        body {
            font-family: system-ui, -apple-system, "Segoe UI", Tahoma, sans-serif;
            background: var(--bg); color: var(--text);
            min-height: 100vh; padding: 20px 16px 40px;
        }
        .container { max-width: 520px; margin: 0 auto; }
        .logo {
            font-size: 1.75rem; font-weight: 800; text-align: center;
            background: linear-gradient(135deg, #818CF8, #22D3EE);
            -webkit-background-clip: text; -webkit-text-fill-color: transparent; background-clip: text;
        }
        .subtitle { color: var(--text2); text-align: center; margin: 6px 0 24px; font-size: 0.9rem; }
        .section {
            background: var(--surface); border: 1px solid #334155; border-radius: 16px;
            padding: 18px; margin-bottom: 18px;
        }
        .section h3 { font-size: 1rem; margin-bottom: 14px; color: var(--secondary); }
        .file-card {
            background: #0F172A; border: 1px solid #334155; border-radius: 12px;
            padding: 12px 14px; margin-bottom: 10px;
            display: flex; align-items: center; justify-content: space-between; gap: 10px;
        }
        .file-name { font-weight: 600; font-size: 0.9rem; word-break: break-all; }
        .file-size { color: var(--text2); font-size: 0.75rem; margin-top: 2px; }
        .btn {
            background: var(--primary); color: white; text-decoration: none;
            padding: 8px 14px; border-radius: 10px; font-weight: 600; font-size: 0.8rem;
            border: none; cursor: pointer; display: inline-block;
        }
        .btn:hover { background: var(--primary-light); }
        .btn-sec { background: var(--secondary); color: #0B1120; }
        .empty { color: var(--text2); text-align: center; padding: 12px; font-size: 0.85rem; }
        .upload-row { display: flex; flex-direction: column; gap: 12px; }
        input[type=file] {
            width: 100%; padding: 12px; background: #0F172A; border: 1px dashed #475569;
            border-radius: 12px; color: var(--text); font-size: 0.85rem;
        }
        .footer { text-align: center; margin-top: 20px; color: var(--text2); font-size: 0.75rem; }
        .received { color: var(--ok); font-size: 0.8rem; text-align: center; margin-bottom: 12px; }
    </style>
</head>
<body>
    <div class="container">
        <div class="logo">Bobbit Share</div>
        <div class="subtitle">اتصال محلی — دانلود و آپلود</div>
        $receivedNote
        <div class="section">
            <h3>دانلود از گوشی</h3>
            $fileRows
        </div>
        <div class="section">
            <h3>ارسال فایل به گوشی</h3>
            <form class="upload-row" action="/upload" method="post" enctype="multipart/form-data">
                <input type="file" name="file" required>
                <button class="btn btn-sec" type="submit">ارسال به گوشی</button>
            </form>
        </div>
        <div class="footer">Bobbit Share · هر دو دستگاه روی یک وای‌فای</div>
    </div>
</body>
</html>
        """.trimIndent()
    }

    private fun escapeHtml(text: String): String {
        return text
            .replace("&", "&")
            .replace("<", "<")
            .replace(">", ">")
            .replace("\"", """)
    }

    fun getActiveClients(): Int = activeConnections.get()

    companion object {
        private const val TAG = "FileServer"
    }
}
