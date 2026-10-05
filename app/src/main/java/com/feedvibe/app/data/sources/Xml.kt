package com.feedvibe.app.data.sources

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader

/** Árbol XML mínimo: suficiente para RSS 2.0, Atom y extensiones media/itunes/yt. */
class XmlNode(val name: String, val attrs: Map<String, String>) {
    val children = mutableListOf<XmlNode>()
    var text: String = ""
        internal set

    /** Nombre sin prefijo de espacio de nombres ("media:thumbnail" -> "thumbnail"). */
    val localName: String get() = name.substringAfter(':')

    fun child(name: String): XmlNode? = children.firstOrNull { it.matches(name) }
    fun all(name: String): List<XmlNode> = children.filter { it.matches(name) }
    fun childText(name: String): String? = child(name)?.text?.trim()?.takeIf { it.isNotEmpty() }
    fun attr(name: String): String? =
        attrs[name] ?: attrs.entries.firstOrNull { it.key.substringAfter(':') == name }?.value

    /** Busca en profundidad el primer descendiente con ese nombre. */
    fun find(name: String): XmlNode? {
        for (c in children) {
            if (c.matches(name)) return c
            c.find(name)?.let { return it }
        }
        return null
    }

    /** "media:thumbnail" coincide exactamente; "thumbnail" coincide con cualquier prefijo. */
    private fun matches(query: String): Boolean =
        if (query.contains(':')) name.equals(query, ignoreCase = true)
        else localName.equals(query, ignoreCase = true)

    companion object {
        private const val FEATURE_RELAXED = "http://xmlpull.org/v1/doc/features.html#relaxed"

        fun parse(xml: String): XmlNode {
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            // Modo relajado: tolera entidades HTML (&nbsp;...) que muchos feeds incluyen.
            runCatching { parser.setFeature(FEATURE_RELAXED, true) }
            parser.setInput(StringReader(xml.trimStart('﻿', ' ', '\n', '\r', '\t')))

            val root = XmlNode("#root", emptyMap())
            val stack = ArrayDeque<XmlNode>().apply { addLast(root) }
            val texts = ArrayDeque<StringBuilder>().apply { addLast(StringBuilder()) }
            try {
                var event = parser.eventType
                while (event != XmlPullParser.END_DOCUMENT) {
                    when (event) {
                        XmlPullParser.START_TAG -> {
                            val attrs = HashMap<String, String>()
                            for (i in 0 until parser.attributeCount) {
                                attrs[parser.getAttributeName(i)] = parser.getAttributeValue(i)
                            }
                            val node = XmlNode(parser.name, attrs)
                            stack.last().children.add(node)
                            stack.addLast(node)
                            texts.addLast(StringBuilder())
                        }
                        XmlPullParser.TEXT -> texts.last().append(parser.text)
                        XmlPullParser.END_TAG -> if (stack.size > 1) {
                            stack.removeLast().text = texts.removeLast().toString()
                        }
                    }
                    event = parser.next()
                }
            } catch (e: Exception) {
                // Un feed truncado o con errores al final: nos quedamos con lo leído.
                if (root.children.isEmpty()) throw SourceException("El contenido no es un feed XML válido", e)
                while (stack.size > 1) stack.removeLast().text = texts.removeLast().toString()
            }
            return root
        }
    }
}
