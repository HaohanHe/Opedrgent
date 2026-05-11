package top.hsyscn.opedrgent.docx

import android.content.Context
import android.net.Uri
import org.xml.sax.InputSource
import java.io.StringReader
import java.io.InputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

object DocxProcessor {
    fun extractText(context: Context, uri: Uri): String {
        return context.contentResolver.openInputStream(uri)?.use { extractText(it) } ?: ""
    }

    fun extractText(input: InputStream): String {
        val zis = ZipInputStream(input)
        var docXml: String? = null
        var relsXml: String? = null
        var hasNumbering = false

        try {
            var entry = zis.nextEntry
            while (entry != null) {
                when {
                    entry.name == "word/document.xml" -> {
                        docXml = zis.bufferedReader().readText()
                    }
                    entry.name == "word/_rels/document.xml.rels" -> {
                        relsXml = zis.bufferedReader().readText()
                    }
                    entry.name.startsWith("word/numbering") -> {
                        hasNumbering = true
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        } catch (_: Exception) { }

        if (docXml.isNullOrBlank()) return ""

        return parseDocXml(docXml, relsXml, hasNumbering)
    }

    private fun parseDocXml(docXml: String, relsXml: String?, hasNumbering: Boolean): String {
        return try {
            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = false
            val builder = factory.newDocumentBuilder()
            val doc = builder.parse(InputSource(StringReader(docXml)))
            val body = doc.getElementsByTagName("w:body").item(0) ?: return ""

            val sb = StringBuilder()
            processBodyElement(body, sb, 0)
            sb.toString().trim()
        } catch (_: Exception) {
            docXml.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()
        }
    }

    private fun processBodyElement(node: org.w3c.dom.Node, sb: StringBuilder, depth: Int) {
        when (node.nodeType) {
            org.w3c.dom.Node.TEXT_NODE -> {
                val text = node.nodeValue?.replace(Regex("\\s+"), " ")?.trim() ?: ""
                if (text.isNotEmpty()) sb.append(text).append(" ")
            }
            org.w3c.dom.Node.ELEMENT_NODE -> {
                val tag = node.nodeName
                val isBlock = tag in listOf("w:p", "w:tr")
                if (isBlock && sb.isNotEmpty() && !sb.endsWith("\n")) sb.append("\n")

                for (i in 0 until node.childNodes.length) {
                    processBodyElement(node.childNodes.item(i), sb, depth + 1)
                }

                if (isBlock && !sb.endsWith("\n")) sb.append("\n")
            }
        }
    }
}
