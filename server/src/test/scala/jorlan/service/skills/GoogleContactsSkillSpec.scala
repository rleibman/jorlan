/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan.service.skills

import jorlan.*
import jorlan.connector.InvocationContext
import jorlan.db.repository.ZIORepositories
import jorlan.*
import jorlan.google.GoogleContactsSkill
import jorlan.service.{GoogleContact, GoogleContactsProvider as GoogleContactsProviderTrait}
import jorlan.testing.InMemoryRepositories
import zio.*
import zio.json.ast.Json
import zio.test.*
import zio.test.TestAspect.*

object GoogleContactsSkillSpec extends ZIOSpec[ZIORepositories] {

  override val bootstrap: ULayer[ZIORepositories] = InMemoryRepositories.live()

  private val ctx = InvocationContext(UserId(1L), None, None)

  private val alice = GoogleContact(
    resourceName = "people/c111",
    displayName = Some("Alice Smith"),
    emails = List("alice@example.com"),
    phones = List("+1-555-0101"),
    organizations = List("Acme Corp"),
  )

  private val bob = GoogleContact(
    resourceName = "people/c222",
    displayName = Some("Bob Jones"),
    emails = List("bob@example.com"),
    phones = Nil,
    organizations = Nil,
  )

  private def makeProvider(contacts: List[GoogleContact] = List(alice, bob)): UIO[FakeGoogleContactsProvider] =
    Ref.make(contacts).map(FakeGoogleContactsProvider(_))

  private def makeSkill(provider: FakeGoogleContactsProvider): URIO[ZIORepositories, GoogleContactsSkill] =
    ZIO.serviceWith[ZIORepositories](new GoogleContactsSkill(provider, _))

  private def intField(
    json: Json,
    key:  String,
  ): Option[Int] =
    json match {
      case Json.Obj(fields) => fields.collectFirst { case (`key`, Json.Num(n)) => n.intValue }
      case _                => None
    }

  private def strField(
    json: Json,
    key:  String,
  ): Option[String] =
    json match {
      case Json.Obj(fields) => fields.collectFirst { case (`key`, Json.Str(v)) => v }
      case _                => None
    }

  override def spec: Spec[ZIORepositories & TestEnvironment & Scope, Any] =
    suite("GoogleContactsSkill")(
      test("google_contacts.list_contacts returns all contacts") {
        for {
          provider <- makeProvider()
          skill    <- makeSkill(provider)
          result   <- skill.invoke(ctx, "google_contacts.list_contacts", Json.Obj())
        } yield assertTrue(intField(result, "count").contains(2))
      },
      test("google_contacts.list_contacts respects maxResults") {
        for {
          provider <- makeProvider()
          skill    <- makeSkill(provider)
          result   <- skill.invoke(
            ctx,
            "google_contacts.list_contacts",
            Json.Obj("maxResults" -> Json.Num(1)),
          )
        } yield assertTrue(intField(result, "count").contains(1))
      },
      test("google_contacts.search_contacts filters by query") {
        for {
          provider <- makeProvider()
          skill    <- makeSkill(provider)
          result   <- skill.invoke(
            ctx,
            "google_contacts.search_contacts",
            Json.Obj("query" -> Json.Str("Alice")),
          )
        } yield assertTrue(
          intField(result, "count").contains(1),
          strField(result, "query").contains("Alice"),
        )
      },
      test("google_contacts.search_contacts requires query field") {
        for {
          provider <- makeProvider()
          skill    <- makeSkill(provider)
          result   <- skill.invoke(ctx, "google_contacts.search_contacts", Json.Obj()).either
        } yield assertTrue(result.isLeft)
      },
      test("google_contacts.get_contact returns contact by resourceName") {
        for {
          provider <- makeProvider()
          skill    <- makeSkill(provider)
          result   <- skill.invoke(
            ctx,
            "google_contacts.get_contact",
            Json.Obj("resourceName" -> Json.Str("people/c111")),
          )
        } yield result match {
          case Json.Obj(fields) =>
            val found = fields.collectFirst { case ("found", Json.Bool(v)) => v }
            val contact = fields.collectFirst { case ("contact", c) => c }
            val name = contact.flatMap {
              case Json.Obj(fs) => fs.collectFirst { case ("displayName", Json.Str(v)) => v }
              case _            => None
            }
            assertTrue(found.contains(true), name.contains("Alice Smith"))
          case _ => assertTrue(false)
        }
      },
      test("google_contacts.get_contact returns not found for unknown resourceName") {
        for {
          provider <- makeProvider()
          skill    <- makeSkill(provider)
          result   <- skill.invoke(
            ctx,
            "google_contacts.get_contact",
            Json.Obj("resourceName" -> Json.Str("people/c999")),
          )
        } yield result match {
          case Json.Obj(fields) =>
            val found = fields.collectFirst { case ("found", Json.Bool(v)) => v }
            assertTrue(found.contains(false))
          case _ => assertTrue(false)
        }
      },
      test("google_contacts.get_contact requires resourceName field") {
        for {
          provider <- makeProvider()
          skill    <- makeSkill(provider)
          result   <- skill.invoke(ctx, "google_contacts.get_contact", Json.Obj()).either
        } yield assertTrue(result.isLeft)
      },
      test("unknown tool returns JorlanError") {
        for {
          provider <- makeProvider()
          skill    <- makeSkill(provider)
          result   <- skill.invoke(ctx, "google_contacts.unknown", Json.Obj()).either
        } yield assertTrue(result.isLeft)
      },
    )

}

class FakeGoogleContactsProvider(contactsRef: Ref[List[GoogleContact]])
    extends GoogleContactsProviderTrait[[A] =>> IO[JorlanError, A]] {

  override def searchContacts(
    userId:     UserId,
    query:      String,
    maxResults: Int,
  ): IO[JorlanError, List[GoogleContact]] =
    contactsRef.get.map { contacts =>
      val q = query.toLowerCase
      contacts
        .filter { c =>
          c.displayName.exists(_.toLowerCase.contains(q)) ||
          c.emails.exists(_.toLowerCase.contains(q)) ||
          c.organizations.exists(_.toLowerCase.contains(q))
        }
        .take(maxResults)
    }

  override def listContacts(
    userId:     UserId,
    maxResults: Int,
  ): IO[JorlanError, List[GoogleContact]] =
    contactsRef.get.map(_.take(maxResults))

  override def getContact(
    userId:       UserId,
    resourceName: String,
  ): IO[JorlanError, Option[GoogleContact]] =
    contactsRef.get.map(_.find(_.resourceName == resourceName))

}
