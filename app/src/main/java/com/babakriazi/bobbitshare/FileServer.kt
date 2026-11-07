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
                method == Method.POST && uri.startsWith("/upload") -> handleUpload(session)
                uri == "/" || uri == "/index.html" -> serveIndex()
                uri.startsWith("/download/") -> serveFile(uri.removePrefix("/download/"))
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
            for ((key, tempPath) in filesMap) {
                if (tempPath.isNullOrBlank()) continue
                val tempFile = File(tempPath)
                if (!tempFile.exists()) continue
                var name = parms[key] ?: parms["file"] ?: tempFile.name
                if (name.isBlank() || name == tempPath) {
                    name = "upload_${System.currentTimeMillis()}"
                }
                name = name.replace('/', '_').replace('\\', '_').trim()
                if (name.isBlank()) name = "upload_${System.currentTimeMillis()}"
                saveUploadedFile(tempFile, name)
                savedName = name
                receivedFiles.add(name)
                onFileReceived?.invoke(name)
                try { tempFile.delete() } catch (_: Exception) {}
            }
            if (savedName == null) {
                return newFixedLengthResponse(
                    Response.Status.BAD_REQUEST,
                    "text/html; charset=utf-8",
                    simplePage("فایلی دریافت نشد", false)
                )
            }
            newFixedLengthResponse(
                Response.Status.OK,
                "text/html; charset=utf-8",
                simplePage("فایل ذخیره شد: $savedName", true)
            )
        } catch (e: Exception) {
            Log.e(TAG, "upload failed", e)
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "text/html; charset=utf-8",
                simplePage("خطا: ${e.message}", false)
            )
        }
    }

    private fun saveUploadedFile(tempFile: File, name: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val itemUri = resolver.insert(collection, values)
                ?: throw IllegalStateException("MediaStore insert failed")
            resolver.openOutputStream(itemUri)?.use { out ->
                FileInputStream(tempFile).use { input -> input.copyTo(out) }
            } ?: throw IllegalStateException("openOutputStream failed")
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(itemUri, values, null, null)
        } else {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!dir.exists()) dir.mkdirs()
            FileInputStream(tempFile).use { input ->
                FileOutputStream(File(dir, name)).use { out -> input.copyTo(out) }
            }
        }
        Log.d(TAG, "Saved upload: $name")
    }

    private fun simplePage(message: String, ok: Boolean): String {
        val color = if (ok) "#22C55E" else "#EF4444"
        return "<!DOCTYPE html><html lang=fa dir=rtl><head><meta charset=utf-8>" +
            "<meta name=viewport content=\"width=device-width,initial-scale=1\">" +
            "<meta http-equiv=refresh content=\"2;url=/\">" +
            "<title>Bobbit Share</title></head>" +
            "<body style=\"font-family:sans-serif;background:#0B1120;color:#F1F5F9;text-align:center;padding:40px\">" +
            "<h2 style=\"color:$color\">$message</h2>" +
            "<p><a href=/ style=\"color:#38BDF8\">بازگشت</a></p></body></html>"
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
            response.addHeader("Content-Disposition", "attachment; filename=\"${file.name}\"")
            response
        } catch (e: Exception) {
            Log.e(TAG, "serveFile", e)
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, e.message)
        }
    }

    private fun buildHtmlPage(): String {
        val sb = StringBuilder()
        sb.append("<!DOCTYPE html><html lang=fa dir=rtl><head><meta charset=utf-8>")
        sb.append("<meta name=viewport content=\"width=device-width,initial-scale=1\">")
        sb.append("<title>Bobbit Share</title><style>")
        sb.append("body{font-family:system-ui,sans-serif;background:#0B1120;color:#F1F5F9;margin:0;padding:20px}")
        sb.append(".c{max-width:520px;margin:0 auto}")
        sb.append(".logo{font-size:1.6rem;font-weight:800;text-align:center;color:#818CF8}")
        sb.append(".sub{color:#94A3B8;text-align:center;margin:8px 0 20px;font-size:.9rem}")
        sb.append(".sec{background:#1E293B;border-radius:16px;padding:16px;margin-bottom:16px}")
        sb.append(".sec h3{color:#22D3EE;margin:0 0 12px;font-size:1rem}")
        sb.append(".fc{background:#0F172A;border-radius:12px;padding:12px;margin-bottom:10px;")
        sb.append("display:flex;justify-content:space-between;align-items:center;gap:10px}")
        sb.append(".btn{background:#6366F1;color:#fff;text-decoration:none;padding:8px 14px;")
        sb.append("border-radius:10px;font-weight:600;font-size:.8rem;border:none;cursor:pointer}")
        sb.append(".btn2{background:#22D3EE;color:#0B1120}")
        sb.append(".empty{color:#94A3B8;text-align:center;padding:12px;font-size:.85rem}")
        sb.append("input[type=file]{width:100%;padding:12px;background:#0F172A;border:1px dashed #475569;")
        sb.append("border-radius:12px;color:#F1F5F9;margin-bottom:12px}")
        sb.append("</style></head><body><div class=c>")
        sb.append("<div class=logo>Bobbit Share</div>")
        sb.append("<div class=sub>دانلود از گوشی و ارسال به گوشی</div>")
        sb.append("<div class=sec><h3>دانلود از گوشی</h3>")
        if (files.isEmpty()) {
            sb.append("<div class=empty>فایلی برای دانلود نیست — می‌توانید از پایین بفرستید</div>")
        } else {
            for (f in files) {
                val enc = URLEncoder.encode(f.name, "UTF-8")
                sb.append("<div class=fc><div><b>").append(escapeHtml(f.name))
                sb.append("</b><br><span style=color:#94A3B8;font-size:.8rem>")
                sb.append(escapeHtml(f.formattedSize())).append("</span></div>")
                sb.append("<a class=btn href=/download/").append(enc).append(">دانلود</a></div>")
            }
        }
        sb.append("</div><div class=sec><h3>ارسال فایل به گوشی</h3>")
        sb.append("<form action=/upload method=post enctype=multipart/form-data>")
        sb.append("<input type=file name=file required>")
        sb.append("<button class=\"btn btn2\" type=submit>ارسال به گوشی</button></form></div>")
        sb.append("<p style=text-align:center;color:#94A3B8;font-size:.75rem>هر دو دستگاه روی یک وای‌فای</p>")
        sb.append("</div></body></html>")
        return sb.toString()
    }

    private fun escapeHtml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
    }

    companion object {
        private const val TAG = "FileServer"
    }
}
