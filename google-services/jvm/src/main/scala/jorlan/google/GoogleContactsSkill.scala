/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan.google

import jorlan.*
import jorlan.connector.{InvocationContext, Skill, SkillDescriptor, ToolDescriptor}
import jorlan.service.{GoogleContact, GoogleContactsProvider as GoogleContactsProviderTrait}
import just.semver.SemVer
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.json.literal.*

/** Built-in skill for reading contacts from Google Contacts (People API).
  *
  * Tools:
  *   - `google_contacts.list_contacts` — list all contacts for the authenticated user
  *   - `google_contacts.search_contacts` — full-text search across contacts
  *   - `google_contacts.get_contact` — retrieve a single contact by resource name
  */
class GoogleContactsSkill(
  contactsProvider: GoogleContactsProviderTrait[[A] =>> IO[JorlanError, A]],
) extends Skill {

  override val descriptor: SkillDescriptor = SkillDescriptor(
    name = "google_contacts",
    tier = SkillTier.BuiltIn,
    skillVersion = SemVer.parse(skill.BuildInfo.version).getOrElse(skill.BuildInfo.version),
    keywords = List(
      "contacts",
      "address book",
      "people",
      "phone number",
      "Google Contacts",
      "vCard",
      "name",
      "email address",
      "organization",
      "find person",
      "lookup contact",
    ),
    tools = List(
      ToolDescriptor(
        name = "google_contacts.list_contacts",
        description = "List contacts from the authenticated user's Google Contacts.",
        inputSchema =
          json"""{"type":"object","properties":{"maxResults":{"type":"integer","description":"Maximum number of contacts to return (default 50)"}},"required":[]}""",
        outputSchema = Json.Obj("type" -> Json.Str("object")),
        requiredCapabilities = List(CapabilityName("google_contacts.read")),
        examplePrompts = List(
          "List my Google contacts",
          "Show me all my contacts",
          "Who are my contacts?",
        ),
      ),
      ToolDescriptor(
        name = "google_contacts.search_contacts",
        description = "Search Google Contacts by name, email, or other text.",
        inputSchema =
          json"""{"type":"object","properties":{"query":{"type":"string","description":"Search term (name, email, phone, etc.)"},"maxResults":{"type":"integer","description":"Maximum number of results (default 20)"}},"required":["query"]}""",
        outputSchema = Json.Obj("type" -> Json.Str("object")),
        requiredCapabilities = List(CapabilityName("google_contacts.read")),
        examplePrompts = List(
          "Find contact Alice in Google Contacts",
          "Search my contacts for 'Smith'",
          "Look up Bob's phone number in my contacts",
        ),
      ),
      ToolDescriptor(
        name = "google_contacts.get_contact",
        description = "Retrieve a specific Google Contact by its People API resource name.",
        inputSchema =
          json"""{"type":"object","properties":{"resourceName":{"type":"string","description":"People API resource name, e.g. 'people/c1234567890'"}},"required":["resourceName"]}""",
        outputSchema = Json.Obj("type" -> Json.Str("object")),
        requiredCapabilities = List(CapabilityName("google_contacts.read")),
        examplePrompts = List(
          "Get the contact details for people/c1234567890",
        ),
      ),
    ),
  )

  override def invoke(
    ctx:  InvocationContext,
    tool: String,
    args: Json,
  ): IO[JorlanError, Json] =
    tool match {
      case "google_contacts.list_contacts"   => listContacts(ctx, args)
      case "google_contacts.search_contacts" => searchContacts(ctx, args)
      case "google_contacts.get_contact"     => getContact(ctx, args)
      case other                             => ZIO.fail(JorlanError(s"GoogleContactsSkill: unknown tool '$other'"))
    }

  private def contactToJson(c: GoogleContact): Json =
    Json.Obj(
      "resourceName"  -> Json.Str(c.resourceName),
      "displayName"   -> c.displayName.fold[Json](Json.Null)(Json.Str(_)),
      "emails"        -> Json.Arr(c.emails.map(Json.Str(_))*),
      "phones"        -> Json.Arr(c.phones.map(Json.Str(_))*),
      "organizations" -> Json.Arr(c.organizations.map(Json.Str(_))*),
    )

  private def listContacts(
    ctx:  InvocationContext,
    args: Json,
  ): IO[JorlanError, Json] = {
    val maxResults = int(args, "maxResults").getOrElse(50)
    for {
      contacts <- contactsProvider.listContacts(ctx.actorId, maxResults)
    } yield Json.Obj(
      "contacts" -> Json.Arr(contacts.map(contactToJson)*),
      "count"    -> Json.Num(contacts.size),
    )
  }

  private def searchContacts(
    ctx:  InvocationContext,
    args: Json,
  ): IO[JorlanError, Json] =
    str(args, "query") match {
      case None        => ZIO.fail(JorlanError("google_contacts.search_contacts: query is required"))
      case Some(query) =>
        val maxResults = int(args, "maxResults").getOrElse(20)
        for {
          contacts <- contactsProvider.searchContacts(ctx.actorId, query, maxResults)
        } yield Json.Obj(
          "contacts" -> Json.Arr(contacts.map(contactToJson)*),
          "count"    -> Json.Num(contacts.size),
          "query"    -> Json.Str(query),
        )
    }

  private def getContact(
    ctx:  InvocationContext,
    args: Json,
  ): IO[JorlanError, Json] =
    str(args, "resourceName") match {
      case None               => ZIO.fail(JorlanError("google_contacts.get_contact: resourceName is required"))
      case Some(resourceName) =>
        for {
          contactOpt <- contactsProvider.getContact(ctx.actorId, resourceName)
        } yield contactOpt match {
          case None          => Json.Obj("found" -> Json.Bool(false), "resourceName" -> Json.Str(resourceName))
          case Some(contact) => Json.Obj("found" -> Json.Bool(true), "contact" -> contactToJson(contact))
        }
    }

}

object GoogleContactsSkill
