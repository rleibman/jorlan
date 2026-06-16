/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan

import zio.json.{JsonDecoder, JsonEncoder}

/** Communication style for the server personality. Shapes the tone of synthesised system prompt instructions.
  *   - `Casual` — relaxed, conversational tone
  *   - `Professional` — formal, precise business language
  *   - `Academic` — scholarly language with citations where appropriate
  *   - `Technical` — expert terminology with detailed explanations
  *   - `Quirky` — playful, offbeat personality with unexpected angles
  *   - `Fresh` — upbeat, modern, enthusiastic tone
  *   - `Rude` — blunt, direct, unfiltered (use with caution)
  *   - `Boomer` — references 1960s–1980s culture, phone-it-in pragmatism, likely older audience so requires more
  *     hand-holding
  *   - `GenX` — sardonic, self-reliant, skeptical of hype
  *   - `Millennial` — culturally fluent, collaborative, occasionally ironic
  *   - `GenZ` — internet-native, brief, emoji-adjacent wit
  *   - `GenAlpha` — hyper-digital, gamified framing, maximum engagement
  *   - `Custom` — no additional instructions; admin must write the full prompt in `Personality.prompt`
  */
enum Formality derives JsonEncoder {

  case Casual, Professional, Academic, Technical, Quirky, Fresh, Rude, Boomer, GenX, Millennial, GenZ, GenAlpha, Custom

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
      case Formality.Quirky =>
        "You are a quirky, offbeat assistant with an unexpected perspective. Surprise the user with unusual angles and playful wit."
      case Formality.Fresh =>
        "You are an upbeat, enthusiastic assistant. Keep the energy high, celebrate small wins, and maintain an optimistic modern tone."
      case Formality.Rude =>
        "You are a blunt, unfiltered assistant. Skip pleasantries, say exactly what you think, and tolerate no nonsense."
      case Formality.Boomer =>
        "You are an assistant who grew up in the 1960s-1980s. Reference classic culture, prefer phone calls, and bring a pragmatic, seen-it-all attitude."
      case Formality.GenX =>
        "You are a sardonic, self-reliant assistant with a healthy skepticism of hype. Keep it real, keep it brief, and don't oversell anything."
      case Formality.Millennial =>
        "You are a culturally fluent, collaborative assistant. Balance enthusiasm with irony, embrace pop-culture references, and prioritise inclusivity."
      case Formality.GenZ =>
        "You are an internet-native assistant. Be brief, direct, and unafraid of dry humour or emoji-adjacent wit. No corporate speak."
      case Formality.GenAlpha =>
        "You are a hyper-digital assistant tuned for maximum engagement. Frame everything as a quest, gamify the interaction, and keep responses fast and punchy."
      case Formality.Custom => ""
    }

    val langInstr =
      if (p.languages.isEmpty || p.languages == List("en")) ""
      else
        s"You can respond in the following languages: ${p.languages.mkString(", ")}. " +
          "Match the language used by the user whenever possible."

    val expertiseInstr =
      if (p.expertise.isEmpty) ""
      else s"You have deep expertise in: ${p.expertise.mkString(", ")}."

    val nameInstr = s"Your name is ${p.name}. When asked your name, always answer '${p.name}'."

    List(nameInstr, formalityInstr, langInstr, expertiseInstr, p.prompt)
      .filter(_.nonEmpty)
      .mkString("\n\n")
  }

}
