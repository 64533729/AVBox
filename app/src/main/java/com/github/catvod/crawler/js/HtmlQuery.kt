package com.github.catvod.crawler.js

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

object HtmlQuery {

    private const val MAX_HTML = 2_097_152

    private const val MAX_PLAN = 16_384

    private const val MAX_RESULT = 1_048_576

    private const val MAX_QUERIES = 16

    private const val MAX_FIELDS = 32

    private const val MAX_ROWS = 1024

    private val gson = GsonBuilder().serializeNulls().create()

    @Throws(IllegalArgumentException::class)
    fun query(html: String, planJson: String): String {
        require(html.length <= MAX_HTML) { "html exceeds $MAX_HTML characters" }
        require(planJson.length <= MAX_PLAN) { "plan exceeds $MAX_PLAN characters" }
        val plan = JsonParser.parseString(planJson).asJsonObject
        requireKeys(plan, setOf("queries"), "plan")
        val queries = plan.getAsJsonObject("queries") ?: throw IllegalArgumentException("queries is required")
        require(queries.size() <= MAX_QUERIES) { "too many queries" }
        val document = Jsoup.parse(html)
        val result = LinkedHashMap<String, Any?>()
        for ((name, value) in queries.entrySet()) {
            val query = value.asJsonObject
            requireKeys(query, setOf("selector", "fields", "maxRows"), "query")
            val fields = query.getAsJsonObject("fields") ?: throw IllegalArgumentException("fields is required")
            require(fields.size() in 1..MAX_FIELDS) { "fields must be 1..$MAX_FIELDS" }
            val maxRows = query.get("maxRows")?.takeUnless { it.isJsonNull }?.asInt ?: MAX_ROWS
            require(maxRows in 1..MAX_ROWS) { "maxRows must be 1..$MAX_ROWS" }
            val selector = query.get("selector")?.takeUnless { it.isJsonNull }?.asString
            val rows = if (selector.isNullOrBlank()) listOf(document) else document.select(selector)
            require(rows.size <= maxRows) { "query $name exceeds maxRows" }
            val items = ArrayList<Map<String, Any?>>(rows.size)
            for (row in rows) {
                val item = LinkedHashMap<String, Any?>()
                for ((fieldName, fieldValue) in fields.entrySet()) {
                    val field = fieldValue.asJsonObject
                    requireKeys(field, setOf("selector", "attribute", "all"), "field")
                    val fieldSelector = field.get("selector")?.takeUnless { it.isJsonNull }?.asString
                    val attribute = field.get("attribute")?.takeUnless { it.isJsonNull }?.asString
                    val all = field.get("all")?.takeUnless { it.isJsonNull }?.asBoolean ?: false
                    if (all) {
                        val elements = if (fieldSelector.isNullOrBlank()) listOf(row) else row.select(fieldSelector)
                        item[fieldName] = elements.map { read(it, attribute) }
                    } else {
                        val element = if (fieldSelector.isNullOrBlank()) row else row.selectFirst(fieldSelector)
                        item[fieldName] = element?.let { read(it, attribute) }
                    }
                }
                items.add(item)
            }
            result[name] = items
        }
        val json = gson.toJson(result)
        require(json.length <= MAX_RESULT) { "result exceeds $MAX_RESULT characters" }
        return json
    }

    private fun read(element: Element, attribute: String?): String? {
        if (attribute.isNullOrEmpty()) return element.text()
        return if (element.hasAttr(attribute)) element.attr(attribute) else null
    }

    private fun requireKeys(obj: JsonObject, allowed: Set<String>, name: String) {
        for (key in obj.keySet()) {
            require(allowed.contains(key)) { "$name has unknown option: $key" }
        }
    }
}
