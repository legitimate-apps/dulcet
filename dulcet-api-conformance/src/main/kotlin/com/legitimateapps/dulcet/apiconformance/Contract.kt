package com.legitimateapps.dulcet.apiconformance

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.networknt.schema.JsonSchema
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SchemaLocation
import com.networknt.schema.SchemaValidatorsConfig
import com.networknt.schema.SpecVersion
import com.networknt.schema.oas.OpenApi31
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * The Dulcet API contract, `openapi.yaml`, used as the oracle for every response the suite sees.
 *
 * A response is checked against the operation it answered (found by `operationId`): its status must
 * be documented or covered by `default`, its media type must be one the contract lists for that
 * status, and a JSON body must validate against that response's schema. Unknown fields pass, as the
 * contract says; formats (`date-time`, `uri`, `uuid`) are asserted.
 */
class Contract private constructor(
    private val root: JsonNode,
    private val documentIri: String,
    val sha256: String,
) {
    data class Operation(val id: String, val method: String, val path: String)

    val version: String = root.path("info").path("version").asText()

    val operations: Map<String, Operation> = buildMap {
        root.path("paths").fields().forEach { (path, item) ->
            item.fields().forEach { (method, op) ->
                val id = op.path("operationId").asText("")
                if (id.isNotEmpty()) put(id, Operation(id, method.uppercase(), path))
            }
        }
    }

    private val factory: JsonSchemaFactory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012) { builder ->
        builder.metaSchema(OpenApi31.getInstance()).defaultMetaSchemaIri(OpenApi31.getInstance().iri)
    }
    private val validatorsConfig: SchemaValidatorsConfig =
        SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build()
    private val schemas = HashMap<String, JsonSchema>()

    /** The response object the contract documents for [operationId] and [status], `$ref` resolved, with its JSON pointer. */
    private fun responseFor(operationId: String, status: Int): Pair<String, JsonNode>? {
        val op = operations[operationId] ?: return null
        val responsesPointer = "/paths/${escape(op.path)}/${op.method.lowercase()}/responses"
        val responses = root.at(responsesPointer)
        val key = when {
            responses.has(status.toString()) -> status.toString()
            responses.has("${status / 100}XX") -> "${status / 100}XX"
            responses.has("default") -> "default"
            else -> return null
        }
        var pointer = "$responsesPointer/${escape(key)}"
        var node = responses.path(key)
        repeat(5) {
            val ref = node.path("\$ref").asText("")
            if (ref.startsWith("#/")) {
                pointer = ref.removePrefix("#")
                node = root.at(pointer)
            }
        }
        return pointer to node
    }

    fun documents(operationId: String, status: Int): Boolean = responseFor(operationId, status) != null

    /** True when the contract documents [status] for [operationId] with no body at all (e.g. getMedia's 416). */
    fun documentsEmptyBody(operationId: String, status: Int): Boolean {
        val content = responseFor(operationId, status)?.second?.path("content") ?: return false
        return content.isMissingNode || content.isEmpty
    }

    /** Every way [body] departs from the contract for this operation and status. Empty means it conforms. */
    fun validate(operationId: String, status: Int, contentType: String?, body: ByteArray, json: JsonNode?): List<String> {
        if (operationId !in operations) return listOf("suite bug: unknown operationId $operationId")
        val (pointer, response) = responseFor(operationId, status)
            ?: return listOf("$operationId: status $status is not documented and the operation has no default response")
        val content = response.path("content")
        if (content.isMissingNode || content.isEmpty) return emptyList()
        if (status == 304 || status == 204) return emptyList()
        val actual = contentType?.substringBefore(';')?.trim()?.lowercase()
            ?: return listOf("$operationId $status: no Content-Type; the contract lists ${content.fieldNames().asSequence().toList()}")
        val match = content.fieldNames().asSequence().firstOrNull { mediaTypeMatches(it, actual) }
            ?: return listOf("$operationId $status: Content-Type $actual is not one the contract lists (${content.fieldNames().asSequence().toList()})")
        if (!isJson(actual)) return emptyList()
        if (json == null) return listOf("$operationId $status: body is not valid JSON")
        val schemaPointer = "$pointer/content/${escape(match)}/schema"
        if (root.at(schemaPointer).isMissingNode) return emptyList()
        val schema = synchronized(schemas) {
            schemas.getOrPut(schemaPointer) {
                factory.getSchema(SchemaLocation.of("$documentIri#$schemaPointer"), validatorsConfig)
            }
        }
        return schema.validate(json).take(8).map { "$operationId $status: ${it.message}" }
    }

    /** Every way [node] departs from the component schema [name] (`#/components/schemas/<name>`). */
    fun validateComponent(name: String, node: JsonNode): List<String> {
        val pointer = "/components/schemas/$name"
        if (root.at(pointer).isMissingNode) return listOf("suite bug: the contract has no schema $name")
        val schema = synchronized(schemas) {
            schemas.getOrPut(pointer) { factory.getSchema(SchemaLocation.of("$documentIri#$pointer"), validatorsConfig) }
        }
        return schema.validate(node).take(8).map { "$name: ${it.message}" }
    }

    /** Validates [node] against a standalone JSON Schema (an MCP tool's `inputSchema` or `outputSchema`). */
    fun validateStandalone(schemaNode: JsonNode, node: JsonNode): List<String> {
        val key = "standalone:" + schemaNode.toString()
        val schema = synchronized(schemas) { schemas.getOrPut(key) { factory.getSchema(schemaNode, validatorsConfig) } }
        return schema.validate(node).take(8).map { it.message }
    }

    companion object {
        private val yaml = ObjectMapper(YAMLFactory())

        fun isJson(mediaType: String): Boolean = mediaType == "application/json" || mediaType.endsWith("+json")

        fun mediaTypeMatches(documented: String, actual: String): Boolean {
            val d = documented.lowercase()
            return d == actual || d == "*/*" || (d.endsWith("/*") && actual.startsWith(d.removeSuffix("*")))
        }

        private fun escape(segment: String) = segment.replace("~", "~0").replace("/", "~1")

        /** The bundled contract (copied from docs/dulcet-api at build time), or a file the operator names. */
        fun load(file: Path? = null): Contract {
            val (bytes, iri) = if (file != null) {
                Files.readAllBytes(file) to file.toAbsolutePath().toUri().toString()
            } else {
                val stream = Contract::class.java.getResourceAsStream("/dulcet-api/openapi.yaml")
                    ?: error("openapi.yaml is not bundled in this build")
                stream.use { it.readBytes() } to "classpath:dulcet-api/openapi.yaml"
            }
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            return Contract(yaml.readTree(bytes), iri, digest)
        }

        /** The published MCP tool definitions (`docs/dulcet-api/mcp-tools.json`), bundled like the contract. */
        fun mcpTools(): JsonNode {
            val stream = Contract::class.java.getResourceAsStream("/dulcet-api/mcp-tools.json")
                ?: error("mcp-tools.json is not bundled in this build")
            return stream.use { com.legitimateapps.dulcet.apiconformance.json.readTree(it) }
        }
    }
}
