package com.legitimateapps.dulcet.server.api

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * One response element, rendered either as Subsonic XML (attributes, repeated child elements, text)
 * or as Subsonic JSON (fields, arrays for repeated children, `value` for text). Whether a child is a
 * list is stated when it is added, because JSON needs an array even for a list of one.
 */
class Node(val name: String) {
    private val attributes = LinkedHashMap<String, Any>()
    private val singles = LinkedHashMap<String, Node>()
    private val lists = LinkedHashMap<String, MutableList<Node>>()
    private val stringLists = LinkedHashMap<String, List<Any>>()
    private val order = ArrayList<String>()
    var value: String? = null

    operator fun set(key: String, value: Any?) {
        when (value) {
            null -> attributes.remove(key)
            is String, is Number, is Boolean -> attributes[key] = value
            else -> error("unsupported attribute type for $key")
        }
    }

    operator fun get(key: String): Any? = attributes[key]

    /** A single child object. */
    fun child(name: String, build: Node.() -> Unit = {}): Node {
        val node = Node(name).apply(build)
        if (name !in singles) order.add(name)
        singles[name] = node
        return node
    }

    fun put(node: Node): Node {
        if (node.name !in singles) order.add(node.name)
        singles[node.name] = node
        return node
    }

    /** Appends to a repeated child; JSON renders it as an array. */
    fun add(name: String, build: Node.() -> Unit = {}): Node = add(Node(name).apply(build))

    fun add(node: Node): Node {
        val list = lists.getOrPut(node.name) { order.add(node.name); ArrayList() }
        list.add(node)
        return node
    }

    /** Declares a repeated child that renders as `[]` in JSON even when empty. */
    fun emptyList(name: String) {
        lists.getOrPut(name) { order.add(name); ArrayList() }
    }

    fun strings(name: String, values: List<String>) = scalars(name, values)

    fun numbers(name: String, values: List<Number>) = scalars(name, values)

    private fun scalars(name: String, values: List<Any>) {
        if (values.isEmpty()) return
        if (name !in stringLists) order.add(name)
        stringLists[name] = values
    }

    fun toJson(): JsonObject {
        val fields = LinkedHashMap<String, JsonElement>()
        for ((key, value) in attributes) {
            fields[key] = when (value) {
                is String -> JsonPrimitive(value)
                is Boolean -> JsonPrimitive(value)
                is Number -> JsonPrimitive(value)
                else -> error("unreachable")
            }
        }
        for (name in order) {
            singles[name]?.let { fields[name] = it.toJson() }
            lists[name]?.let { list -> fields[name] = JsonArray(list.map { it.toJson() }) }
            stringLists[name]?.let { list -> fields[name] = JsonArray(list.map { if (it is Number) JsonPrimitive(it) else JsonPrimitive(it.toString()) }) }
        }
        value?.let { fields["value"] = JsonPrimitive(it) }
        return JsonObject(fields)
    }

    fun writeXml(out: StringBuilder, rootNamespace: String? = null) {
        out.append('<').append(name)
        if (rootNamespace != null) out.append(" xmlns=\"").append(rootNamespace).append('"')
        for ((key, value) in attributes) {
            out.append(' ').append(key).append("=\"").append(escapeXml(value.toString(), attribute = true)).append('"')
        }
        val hasBody = value != null || singles.isNotEmpty() || lists.values.any { it.isNotEmpty() } || stringLists.isNotEmpty()
        if (!hasBody) {
            out.append("></").append(name).append('>')
            return
        }
        out.append('>')
        for (name in order) {
            singles[name]?.writeXml(out)
            lists[name]?.forEach { it.writeXml(out) }
            stringLists[name]?.forEach { text -> out.append('<').append(name).append('>').append(escapeXml(text.toString(), false)).append("</").append(name).append('>') }
        }
        value?.let { out.append(escapeXml(it, attribute = false)) }
        out.append("</").append(name).append('>')
    }

    companion object {
        fun escapeXml(value: String, attribute: Boolean): String {
            val builder = StringBuilder(value.length)
            for (character in value) {
                when (character) {
                    '&' -> builder.append("&amp;")
                    '<' -> builder.append("&lt;")
                    '>' -> builder.append("&gt;")
                    '"' -> if (attribute) builder.append("&quot;") else builder.append(character)
                    '\'' -> if (attribute) builder.append("&apos;") else builder.append(character)
                    '\n' -> if (attribute) builder.append("&#xA;") else builder.append(character)
                    '\r' -> builder.append("&#xD;")
                    '\t' -> if (attribute) builder.append("&#x9;") else builder.append(character)
                    else -> if (character.code < 0x20) Unit else builder.append(character)
                }
            }
            return builder.toString()
        }
    }
}
