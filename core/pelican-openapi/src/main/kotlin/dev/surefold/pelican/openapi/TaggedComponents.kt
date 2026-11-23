package dev.surefold.pelican.openapi

import dev.surefold.pelican.ErrorSpec
import dev.surefold.pelican.JsonArr
import dev.surefold.pelican.JsonObj
import dev.surefold.pelican.JsonStr
import dev.surefold.pelican.JsonValue
import dev.surefold.pelican.SchemaRegistry
import dev.surefold.pelican.SchemaSource
import dev.surefold.pelican.jsonObj

private const val COMPONENT_PREFIX = "#/components/schemas/"

/**
 * Writes each tagged failure's tag into its component, as a required `const`, because that is what
 * the server sends and what OpenAPI asks of a discriminator: the property it names has to be in
 * every schema the mapping points at. See spec 0066.
 *
 * A component is one schema for every use of its type, so a type used under two tags, or tagged in
 * one place and plain in another, has no single schema that is true. That is refused here, after
 * [rendered] is complete, because only then is every `$ref` to the component known.
 */
internal fun declareTags(
    failures: List<ErrorSpec>,
    schemas: SchemaSource,
    components: SchemaRegistry,
    rendered: List<JsonValue>,
) {
    val tagged = failures.mapNotNull { err ->
        val type = err.type ?: return@mapNotNull null
        if (err.tag == null) return@mapNotNull null
        val ref = (schemas.schema(type, components)["\$ref"] as? JsonStr)?.value ?: return@mapNotNull null
        ref.removePrefix(COMPONENT_PREFIX) to err
    }
    val refs = refCounts(rendered)
    tagged.groupBy({ it.first }, { it.second }).forEach { (name, failuresOfIt) ->
        val uses = failuresOfIt.map { it.tag to it.discriminator }
        val tags = uses.distinct()
        require(tags.size == 1) {
            "$name is tagged ${tags.joinToString(" and ") { (tag, field) -> "`$tag` under `$field`" }}. " +
                "Its schema is one component, and a tag is written into it, so it can only carry one. " +
                "Declare a second type for the second failure."
        }
        require((refs["$COMPONENT_PREFIX$name"] ?: 0) == uses.size) {
            "$name is tagged `${tags.single().first}` as a failure, and also used where it is not tagged. " +
                "Its schema is one component, and a tag is written into it, so the untagged use would be " +
                "documented as carrying a tag it never sends. Declare a second type for one of them."
        }
        val (tag, field) = tags.single().let { (tag, field) -> checkNotNull(tag) to field }
        val schema = components.all()[name] as JsonObj
        // The first use's words, where the type says nothing of its own: one tag, so one failure.
        components.register(name, withTag(name, schema, tag, field, failuresOfIt.first().description))
    }
}

private fun withTag(name: String, schema: JsonObj, tag: String, field: String, description: String): JsonObj {
    val properties = (schema["properties"] as? JsonObj)?.fields.orEmpty()
    require(field !in properties) {
        "$name already has a field `$field`, and its tag is written under that name. Tag it under " +
            "another with tagged(tag, field = \"...\")."
    }
    val required = (schema["required"] as? JsonArr)?.items.orEmpty()
    val declared = jsonObj {
        "type" to "string"
        "const" to tag
    }
    // First, so a reader of the schema meets the field the whole response is told apart by.
    return schema + jsonObj {
        if (schema["description"] == null) "description" to description
        put("properties", JsonObj(mapOf(Pair(field, declared)) + properties))
        put("required", JsonArr(listOf(JsonStr(field)) + required))
    }
}

/** How many `$ref`s in [values] point at each target. A discriminator's mapping names one too, but as a value. */
private fun refCounts(values: List<JsonValue>): Map<String, Int> =
    HashMap<String, Int>().also { counts -> values.forEach { countRefs(it, counts) } }

private fun countRefs(value: JsonValue, counts: MutableMap<String, Int>) {
    when (value) {
        is JsonObj -> value.fields.forEach { (key, v) ->
            val ref = (v as? JsonStr)?.value
            if (key == "\$ref" && ref != null) counts.merge(ref, 1, Int::plus) else countRefs(v, counts)
        }

        is JsonArr -> value.items.forEach { countRefs(it, counts) }

        else -> Unit
    }
}
