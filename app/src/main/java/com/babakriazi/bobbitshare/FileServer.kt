package com.babakriazi.bobbitshare

import android.content.Context
import android.net.Uri
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class FileServer(
    private val context: Context,
    private val files: List<FileItem>,
    port: Int = 8080
) : NanoHTTPD(port) {

    private val activeConnections = AtomicInteger(0)
    private val downloadCounts = ConcurrentHashMap<String, AtomicInteger>()

    var onClientConnected: ((Int) -> Unit)? = null

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method

        // Track connections roughly
        activeConnections.incrementAndGet()
        onClientConnected?.invoke(activeConnections.get())

        return try {
            when {
                uri == "/" || uri == "/index.html" -> serveIndex()
                uri.startsWith("/download/") -> serveFile(uri.removePrefix("/download/"))
                uri == "/zip" -> serveZip()
                uri == "/status" -> newFixedLengthResponse(
                    Response.Status.OK,
                    "application/json",
                    "{\"files\":${files.size},\"clients\":${activeConnections.get()}}"
                )
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
            }
        } finally {
            // Note: this is approximate; real connection tracking is harder with NanoHTTPD
        }
    }

    private fun serveIndex(): Response {
        val html = buildHtmlPage()
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
    }

    private fun serveFile(encodedName: String): Response {
        val name = java.net.URLDecoder.decode(encodedName, "UTF-8")
        val file = files.find { it.name == name }
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "File not found")

        return try {
            val inputStream: InputStream = context.contentResolver.openInputStream(file.uri)
                ?: return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Cannot open file")

            val response = newFixedLengthResponse(
                Response.Status.OK,
                file.mimeType ?: "application/octet-stream",
                inputStream,
                file.size
            )
            response.addHeader("Content-Disposition", "attachment; filename=\"${file.name}\"")
            response.addHeader("Accept-Ranges", "bytes")
            response
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Error: ${e.message}")
        }
    }

    private fun serveZip(): Response {
        // For simplicity in v1 we skip real zip streaming (can be added later)
        // Instead redirect user to download files one by one
        return newFixedLengthResponse(
            Response.Status.OK,
            "text/html; charset=utf-8",
            """<html><body style="font-family:sans-serif;text-align:center;padding:40px;background:#0B0F19;color:#F1F5F9">
                <h2>دانلود یکجا در نسخه بعدی اضافه می‌شود</h2>
                <p>فعلاً فایل‌ها را تک‌تک دانلود کنید.</p>
                <a href="/" style="color:#818CF8">بازگشت</a>
               </body></html>"""
        )
    }

    private fun buildHtmlPage(): String {
        val fileRows = files.joinToString("") { file ->
            val encoded = java.net.URLEncoder.encode(file.name, "UTF-8")
            """
            <div class="file-card">
                <div class="file-info">
                    <div class="file-name">${escapeHtml(file.name)}</div>
                    <div class="file-size">${file.formattedSize()}</div>
                </div>
                <a class="btn" href="/download/$encoded">دانلود</a>
            </div>
            """
        }

        return """
<!DOCTYPE html>
<html lang="fa" dir="rtl">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0">
    <title>Bobbit Share</title>
    <style>
        :root {
            --bg: #0B0F19;
            --surface: #151B2B;
            --surface2: #1E293B;
            --primary: #6366F1;
            --primary-light: #818CF8;
            --text: #F1F5F9;
            --text2: #94A3B8;
            --success: #34D399;
        }
        * { box-sizing: border-box; margin: 0; padding: 0; }
        body {
            font-family: 'Segoe UI', system-ui, -apple-system, sans-serif;
            background: var(--bg);
            color: var(--text);
            min-height: 100vh;
            padding: 20px;
            line-height: 1.5;
        }
        .container {
            max-width: 640px;
            margin: 0 auto;
        }
        .header {
            text-align: center;
            margin-bottom: 32px;
            padding-top: 12px;
        }
        .logo {
            font-size: clamp(1.8rem, 5vw, 2.4rem);
            font-weight: 800;
            letter-spacing: -0.5px;
            background: linear-gradient(135deg, #818CF8, #22D3EE);
            -webkit-background-clip: text;
            -webkit-text-fill-color: transparent;
            background-clip: text;
        }
        .subtitle {
            color: var(--text2);
            margin-top: 6px;
            font-size: 0.95rem;
        }
        .file-card {
            background: var(--surface);
            border: 1px solid #1E293B;
            border-radius: 16px;
            padding: 16px 18px;
            margin-bottom: 12px;
            display: flex;
            align-items: center;
            justify-content: space-between;
            gap: 12px;
            transition: transform 0.15s, border-color 0.15s;
        }
        .file-card:hover {
            border-color: var(--primary);
            transform: translateY(-1px);
        }
        .file-info { flex: 1; min-width: 0; }
        .file-name {
            font-weight: 600;
            font-size: 0.95rem;
            white-space: nowrap;
            overflow: hidden;
            text-overflow: ellipsis;
        }
        .file-size {
            color: var(--text2);
            font-size: 0.8rem;
            margin-top: 2px;
        }
        .btn {
            background: var(--primary);
            color: white;
            text-decoration: none;
            padding: 10px 18px;
            border-radius: 12px;
            font-weight: 600;
            font-size: 0.85rem;
            white-space: nowrap;
            transition: background 0.15s;
        }
        .btn:hover { background: var(--primary-light); }
        .footer {
            text-align: center;
            margin-top: 28px;
            color: var(--text2);
            font-size: 0.8rem;
        }
        @media (max-width: 480px) {
            body { padding: 14px; }
            .file-card { padding: 14px; }
            .btn { padding: 8px 14px; font-size: 0.8rem; }
        }
    </style>
</head>
<body>
    <div class="container">
        <div class="header">
            <div class="logo">Bobbit Share</div>
            <div class="subtitle">${files.size} فایل آماده دانلود</div>
        </div>
        $fileRows
        <div class="footer">قدرت گرفته از Bobbit Share</div>
    </div>
</body>
</html>
        """.trimIndent()
    }

    private fun escapeHtml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
    }

    fun getActiveClients(): Int = activeConnections.get()
}
