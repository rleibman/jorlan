/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan.domain

import zio.json.{JsonDecoder, JsonEncoder}

/** Communication style for the server personality. Shapes the tone of synthesised system prompt instructions.
  *   - `Casual` — relaxed, conversational tone
  *   - `Professional` — formal, precise business language
  *   - `Academic` — scholarly language with citations where appropriate
  *   - `Technical` — expert terminology with detailed explanations
  */
enum Formality derives JsonEncoder {

  case Casual, Professional, Academic, Technical

}

object Formality {

  /** Case-insensitive decoder — accepts both `"Professional"` (zio-json default) and `"professional"` (legacy seeds).
    */
  given JsonDecoder[Formality] =
    JsonDecoder[String].mapOrFail { s =>
      Formality.values
        .find(_.toString.equalsIgnoreCase(s))
        .toRight(s"Unknown Formality value: '$s'. Expected one of: ${Formality.values.map(_.toString).mkString(", ")}")
    }

}

/** Server-wide personality that shapes how agents communicate on this installation.
  *
  * Stored as a JSON object under key `personality` in `server_settings`. All agents on the server share this
  * personality — it is a floor, not a per-agent setting.
  *
  * @param name
  *   What the assistant calls itself in conversation (mirrors `serverName`).
  * @param formality
  *   Communication style; shapes the synthesised system prompt preamble.
  * @param languages
  *   ISO 639-1 language codes; the assistant tries to match the user's language.
  * @param expertise
  *   Topic areas where the assistant applies extra depth.
  * @param prompt
  *   Free-form prose injected as the system prompt. Admins write whatever they need here.
  */
case class Personality(
  name:      String,
  formality: Formality,
  languages: List[String],
  expertise: List[String],
  prompt:    String,
) derives JsonEncoder, JsonDecoder

object Personality {

  val default: Personality = Personality(
    name = "Jorlan",
    formality = Formality.Professional,
    languages = List("en"),
    expertise = Nil,
    prompt = "You are a capable, thoughtful assistant focused on helping users accomplish their goals efficiently. " +
      "You ask clarifying questions when a request is ambiguous rather than making assumptions. " +
      "You acknowledge uncertainty rather than fabricating answers.",
  )

  /** Builds the system prompt string sent to the model on each call. Formality and language hints are synthesised into
    * natural-language instructions prepended to the admin's free-form `prompt` field.
    */
  def buildSystemPrompt(p: Personality): String = {
    val formalityInstr = p.formality match {
      case Formality.Casual =>
        "You are a casual, friendly assistant. Use conversational language and a relaxed, approachable tone."
      case Formality.Professional =>
        "You are a professional assistant. Communicate formally, precisely, and with appropriate business language."
      case Formality.Academic =>
        "You are a scholarly assistant. Use academic language, precise terminology, and cite sources where appropriate."
      case Formality.Technical =>
        "You are a technical expert assistant. Use technical terminology and provide detailed, accurate explanations."
    }

    val langInstr =
      if (p.languages.isEmpty || p.languages == List("en")) ""
      else
        s"You can respond in the following languages: ${p.languages.mkString(", ")}. " +
          "Match the language used by the user whenever possible."

    val expertiseInstr =
      if (p.expertise.isEmpty) ""
      else s"You have deep expertise in: ${p.expertise.mkString(", ")}."

    List(formalityInstr, langInstr, expertiseInstr, p.prompt)
      .filter(_.nonEmpty)
      .mkString("\n\n")
  }

}
