/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package ai
// $COVERAGE-OFF$

import com.fasterxml.jackson.databind.JsonNode
import dev.langchain4j.agent.tool.ToolSpecification
import dev.langchain4j.model.chat.request.json.*
import dev.langchain4j.model.chat.response.ChatResponse

import scala.jdk.CollectionConverters.*
import scala.language.unsafeNulls

/** A Scala-friendly representation of a tool specification to pass to the model.
  *
  * @param name
  *   Fully qualified tool name (e.g. `"memory.remember"`). Must be unique within a call.
  * @param description
  *   Natural-language description of what the tool does; shown to the model.
  * @param parametersJson
  *   JSON Schema string describing the tool's input object (properties + required).
  */
case class ScalaToolSpec(
  name:           String,
  description:    String,
  parametersJson: String,
)

/** A tool call request returned by the model. */
case class ScalaToolCall(
  id:       String,
  name:     String,
  argsJson: String,
)

object ToolSupport {

  /** Convert a [[ScalaToolSpec]] to a LangChain4j [[ToolSpecification]].
    *
    * Parses `parametersJson` as a JSON Schema subset: `type: object` with `properties`
    * (string/integer/number/boolean/array/object, nested to any depth), an optional `required` array, `enum` on string
    * properties, `items` on arrays, and union types of the form `["string","null"]`.
    */
  def buildToolSpecification(spec: ScalaToolSpec): ToolSpecification = {
    ToolSpecification
      .builder()
      .name(spec.name)
      .description(spec.description)
      .parameters(parseObjectSchema(spec.parametersJson))
      .build()
  }

  private def parseObjectSchema(json: String): JsonObjectSchema = {
    import com.fasterxml.jackson.databind.ObjectMapper
    buildObjectSchema(ObjectMapper().readTree(json))
  }

  /** The declared type, tolerating both `"type": "string"` and union forms like `"type": ["string", "null"]` (which MCP
    * servers emit for nullable fields). Defaults to string when absent.
    */
  private def declaredType(node: JsonNode): String = {
    val t = node.get("type")
    if (t == null) "string"
    else if (t.isArray) t.elements().asScala.map(_.asText).find(_ != "null").getOrElse("string")
    else t.asText
  }

  private def buildObjectSchema(node: JsonNode): JsonObjectSchema = {
    val builder = JsonObjectSchema.builder()

    Option(node.get("description")).foreach(d => builder.description(d.asText))

    val requiredNames: Set[String] =
      Option(node.get("required"))
        .map(_.elements().asScala.map(_.asText).toSet)
        .getOrElse(Set.empty)

    Option(node.get("properties")).foreach { props =>
      props.fieldNames().asScala.foreach { propName =>
        val propNode = props.get(propName)
        val base = buildElement(propNode)

        // Optional properties are made nullable so strict providers (Groq, OpenAI, ...) accept an
        // explicit `null` — which models routinely emit for "not provided" — instead of rejecting the
        // whole tool call with a schema-validation 400. Every skill's arg parsing already treats a
        // `null` field identically to an absent one.
        val element: JsonSchemaElement =
          if (requiredNames.contains(propName) || isNullable(base)) base
          else
            JsonAnyOfSchema
              .builder()
              .description(Option(propNode.get("description")).map(_.asText).orNull)
              .anyOf(base, JsonNullSchema())
              .build()

        builder.addProperty(propName, element)
      }
    }

    if (requiredNames.nonEmpty) builder.required(requiredNames.toList.sorted*)

    builder.build()
  }

  /** True when the element already admits `null`, i.e. it is an `anyOf` with a null branch. A property declared
    * `["string","null"]` is built that way by [[buildElement]], so if it is also optional it must not be wrapped a
    * second time — strict providers reject the resulting `anyOf(anyOf(string, null), null)`.
    */
  private def isNullable(element: JsonSchemaElement): Boolean =
    element match {
      case a: JsonAnyOfSchema => a.anyOf().asScala.exists(_.isInstanceOf[JsonNullSchema])
      case _ => false
    }

  private def buildElement(node: JsonNode): JsonSchemaElement = {
    val desc = Option(node.get("description")).map(_.asText).orNull

    val typeNode = node.get("type")
    val nullable =
      typeNode != null && typeNode.isArray && typeNode.elements().asScala.exists(_.asText == "null")

    val base: JsonSchemaElement =
      declaredType(node) match {
        case "integer" => JsonIntegerSchema.builder().description(desc).build()
        case "number"  => JsonNumberSchema.builder().description(desc).build()
        case "boolean" => JsonBooleanSchema.builder().description(desc).build()
        // Nested objects are recursed into rather than flattened to a string: an MCP tool whose argument is an
        // object (a name, a date, a child reference) is unusable if the model is told to pass a bare string.
        case "object" => buildObjectSchema(node)
        // An array schema with no `items` is rejected outright by strict providers, and tells a model nothing
        // about what to put in the array. Default the element type to string when the server omits it.
        case "array" =>
          val items = Option(node.get("items")).map(buildElement).getOrElse(JsonStringSchema.builder().build())
          JsonArraySchema.builder().description(desc).items(items).build()
        case _ =>
          if (node.has("enum")) {
            val values = node.get("enum").elements().asScala.map(_.asText).toList.asJava
            JsonEnumSchema.builder().enumValues(values).description(desc).build()
          } else {
            JsonStringSchema.builder().description(desc).build()
          }
      }

    if (nullable) JsonAnyOfSchema.builder().description(desc).anyOf(base, JsonNullSchema()).build()
    else base
  }

  /** Extract the first tool call from a [[ChatResponse]], if the model requested one. */
  def extractToolCall(response: ChatResponse): Option[ScalaToolCall] = {
    val msg = response.aiMessage()
    if (msg.hasToolExecutionRequests) {
      val req = msg.toolExecutionRequests().get(0)
      Some(
        ScalaToolCall(
          id = Option(req.id()).getOrElse(""),
          name = req.name(),
          argsJson = Option(req.arguments()).getOrElse("{}"),
        ),
      )
    } else None
  }

}
// $COVERAGE-ON$
