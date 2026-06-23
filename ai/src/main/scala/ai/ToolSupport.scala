/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package ai
// $COVERAGE-OFF$

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
    * Parses `parametersJson` as a JSON Schema subset: `type: object`, `properties` (string/integer/number/boolean),
    * optional `required` array, and optional `enum` on string properties.
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
    val mapper = ObjectMapper()
    val root = mapper.readTree(json)
    val builder = JsonObjectSchema.builder()

    Option(root.get("description")).foreach(d => builder.description(d.asText))

    Option(root.get("properties")).foreach { props =>
      props.fieldNames().asScala.foreach { propName =>
        val propNode = props.get(propName)
        val desc = Option(propNode.get("description")).map(_.asText).orNull
        val propType = Option(propNode.get("type")).map(_.asText).getOrElse("string")

        propType match {
          case "integer" =>
            builder.addIntegerProperty(propName, desc)
          case "number" =>
            builder.addNumberProperty(propName, desc)
          case "boolean" =>
            builder.addBooleanProperty(propName, desc)
          case "array" =>
            builder.addProperty(propName, JsonArraySchema.builder().description(desc).build())
          case _ =>
            if (propNode.has("enum")) {
              val values = propNode.get("enum").elements().asScala.map(_.asText).toList.asJava
              builder.addProperty(propName, JsonEnumSchema.builder().enumValues(values).description(desc).build())
            } else {
              builder.addStringProperty(propName, desc)
            }
        }
      }
    }

    Option(root.get("required")).foreach { reqArr =>
      val fields = reqArr.elements().asScala.map(_.asText).toArray
      if (fields.nonEmpty) builder.required(fields*)
    }

    builder.build()
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
