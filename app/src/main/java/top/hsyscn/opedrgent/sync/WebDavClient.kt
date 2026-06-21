package top.hsyscn.opedrgent.sync

import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import top.hsyscn.opedrgent.utils.DebugLog
import java.io.StringReader
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

/**
 * WebDAV 客户端 — 支持基本的文件同步操作。
 *
 * 支持的操作：
 * - PROPFIND: 列出目录/获取文件属性
 * - GET: 下载文件
 * - PUT: 上传文件
 * - DELETE: 删除文件
 * - MKCOL: 创建目录
 */
class WebDavClient(private val config: WebDavConfig) {

    companion object {
        private const val TAG = "WebDavClient"
        private const val NS_DAV = "DAV:"
    }

    data class WebDavResource(
        val href: String,
        val displayName: String = "",
        val lastModified: Long = 0,
        val contentLength: Long = 0,
        val etag: String = "",
        val isDirectory: Boolean = false,
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private fun buildRequest(method: String, path: String, body: String? = null, mediaType: String? = null): Request.Builder {
        val url = config.resolveUrl(path)
        val builder = Request.Builder()
            .url(url)
            .method(method, body?.toRequestBody((mediaType ?: "application/octet-stream").toMediaType()))
        if (config.username.isNotBlank()) {
            builder.header("Authorization", Credentials.basic(config.username, config.password))
        }
        return builder
    }

    /**
     * 列出目录内容（PROPFIND depth=1）。
     */
    fun listDirectory(path: String = "/"): List<WebDavResource> {
        val body = """<?xml version="1.0" encoding="utf-8"?>
            |<D:propfind xmlns:D="DAV:">
            |  <D:prop>
            |    <D:displayname/>
            |    <D:getlastmodified/>
            |    <D:getcontentlength/>
            |    <D:getetag/>
            |    <D:resourcetype/>
            |  </D:prop>
            |</D:propfind>""".trimMargin()

        val request = buildRequest("PROPFIND", path, body, "application/xml")
            .header("Depth", "1")
            .build()

        val response = client.newCall(request).execute()
        if (response.code !in 200..299) {
            throw WebDavException("PROPFIND 失败: HTTP ${response.code} ${response.message}")
        }

        val xml = response.body?.string() ?: return emptyList()
        return parsePropfindResponse(xml, path)
    }

    /**
     * 下载文件内容。
     */
    fun download(path: String): String? {
        val request = buildRequest("GET", path).build()
        val response = client.newCall(request).execute()
        return when (response.code) {
            200 -> response.body?.string()
            404 -> null
            else -> throw WebDavException("GET 失败: HTTP ${response.code}")
        }
    }

    /**
     * 上传文件内容。
     */
    fun upload(path: String, content: String) {
        val request = buildRequest("PUT", path, content, "text/plain; charset=utf-8").build()
        val response = client.newCall(request).execute()
        if (response.code !in 200..299) {
            throw WebDavException("PUT 失败: HTTP ${response.code} ${response.message}")
        }
        DebugLog.d("$TAG: 上传成功 — $path (${content.length} bytes)")
    }

    /**
     * 删除文件。
     */
    fun delete(path: String) {
        val request = buildRequest("DELETE", path).build()
        val response = client.newCall(request).execute()
        if (response.code !in 200..299 && response.code != 404) {
            throw WebDavException("DELETE 失败: HTTP ${response.code}")
        }
        DebugLog.d("$TAG: 删除成功 — $path")
    }

    /**
     * 创建目录（MKCOL）。
     */
    fun mkdir(path: String) {
        val request = buildRequest("MKCOL", path).build()
        val response = client.newCall(request).execute()
        // 405 = 已存在，也算成功
        if (response.code !in 200..299 && response.code != 405) {
            throw WebDavException("MKCOL 失败: HTTP ${response.code}")
        }
        DebugLog.d("$TAG: 目录创建 — $path")
    }

    /**
     * 检查远端路径是否存在。
     */
    fun exists(path: String): Boolean {
        val request = buildRequest("HEAD", path).build()
        val response = client.newCall(request).execute()
        return response.code == 200
    }

    /**
     * 测试连接是否可用。
     */
    fun testConnection(): Boolean {
        return try {
            listDirectory("/")
            true
        } catch (e: Exception) {
            DebugLog.w("$TAG: 连接测试失败 — ${e.message}")
            false
        }
    }

    /**
     * 解析 PROPFIND XML 响应。
     */
    private fun parsePropfindResponse(xml: String, basePath: String): List<WebDavResource> {
        val resources = mutableListOf<WebDavResource>()
        try {
            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = true
            val builder = factory.newDocumentBuilder()
            val doc = builder.parse(InputSource(StringReader(xml)))
            val responses = doc.getElementsByTagNameNS(NS_DAV, "response")

            for (i in 0 until responses.length) {
                val node = responses.item(i)
                val href = getChildText(node, "href", NS_DAV) ?: continue
                val propNode = findChild(node, "propstat", NS_DAV)
                    ?.let { findChild(it, "prop", NS_DAV) } ?: continue

                val displayName = getChildText(propNode, "displayname", NS_DAV) ?: href.substringAfterLast("/")
                val lastModifiedStr = getChildText(propNode, "getlastmodified", NS_DAV)
                val contentLengthStr = getChildText(propNode, "getcontentlength", NS_DAV)
                val etag = getChildText(propNode, "getetag", NS_DAV) ?: ""
                val resourceType = findChild(propNode, "resourcetype", NS_DAV)
                val isDirectory = resourceType?.let { findChild(it, "collection", NS_DAV) } != null

                resources.add(WebDavResource(
                    href = href,
                    displayName = displayName,
                    lastModified = parseHttpDate(lastModifiedStr),
                    contentLength = contentLengthStr?.toLongOrNull() ?: 0,
                    etag = etag,
                    isDirectory = isDirectory,
                ))
            }
        } catch (e: Exception) {
            DebugLog.w("$TAG: 解析 PROPFIND 响应失败 — ${e.message}")
        }
        return resources
    }

    private fun getChildText(node: org.w3c.dom.Node, localName: String, namespace: String): String? {
        val children = node.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i)
            if (child.localName == localName && child.namespaceURI == namespace) {
                return child.textContent?.trim()
            }
        }
        return null
    }

    private fun findChild(node: org.w3c.dom.Node, localName: String, namespace: String): org.w3c.dom.Node? {
        val children = node.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i)
            if (child.localName == localName && child.namespaceURI == namespace) {
                return child
            }
        }
        return null
    }

    private fun parseHttpDate(dateStr: String?): Long {
        if (dateStr.isNullOrBlank()) return 0
        return try {
            val format = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.ENGLISH)
            format.parse(dateStr)?.time ?: 0
        } catch (e: Exception) {
            0
        }
    }
}

class WebDavException(message: String, cause: Throwable? = null) : Exception(message, cause)
