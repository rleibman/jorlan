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

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.people.v1.PeopleService
import jorlan.JorlanError
import jorlan.*
import jorlan.service.{GoogleContact, GoogleContactsProvider as GoogleContactsProviderTrait, OAuthCredentialService}
import zio.{IO, Ref, ZIO}

import scala.jdk.CollectionConverters.*
import scala.language.unsafeNulls

/** Google People API-backed contacts provider.
  *
  * Access tokens are refreshed lazily via [[OAuthCredentialService.refreshAccessToken]] (only when near expiry).
  */
class GoogleContactsProvider private (
  credentials: OAuthCredentialService,
  transport:   com.google.api.client.http.HttpTransport,
  jsonFactory: com.google.api.client.json.JsonFactory,
  clientCache: Ref[Map[UserId, (String, PeopleService)]],
) extends GoogleApiProvider[PeopleService](credentials, transport, jsonFactory, clientCache)
    with GoogleContactsProviderTrait[[A] =>> IO[JorlanError, A]] {

  override protected val apiName: String = "People"

  override protected def makeClient(accessToken: String): PeopleService =
    new PeopleService.Builder(transport, jsonFactory, buildAdapter(accessToken))
      .setApplicationName("Jorlan")
      .build()
      .nn

  private val personFields = "names,emailAddresses,phoneNumbers,organizations"

  private def toDomain(person: com.google.api.services.people.v1.model.Person): GoogleContact = {
    val displayName = Option(person.getNames)
      .map(_.asScala.toList).getOrElse(Nil)
      .headOption
      .flatMap(n => Option(n.getDisplayName))
    val emails = Option(person.getEmailAddresses)
      .map(_.asScala.toList).getOrElse(Nil)
      .flatMap(e => Option(e.getValue))
    val phones = Option(person.getPhoneNumbers)
      .map(_.asScala.toList).getOrElse(Nil)
      .flatMap(p => Option(p.getValue))
    val orgs = Option(person.getOrganizations)
      .map(_.asScala.toList).getOrElse(Nil)
      .flatMap(o => Option(o.getName))
    GoogleContact(
      resourceName = Option(person.getResourceName).getOrElse("").nn,
      displayName = displayName,
      emails = emails,
      phones = phones,
      organizations = orgs,
    )
  }

  /** Search contacts using the People API full-text search. */
  override def searchContacts(
    userId:     UserId,
    query:      String,
    maxResults: Int,
  ): IO[JorlanError, List[GoogleContact]] =
    withClient(userId) { svc =>
      val result = svc
        .people()
        .searchContacts()
        .nn
        .setQuery(query)
        .setPageSize(maxResults)
        .setReadMask(personFields)
        .nn
        .execute()
        .nn
      Option(result.getResults)
        .map(_.asScala.toList).getOrElse(Nil)
        .flatMap(r => Option(r.getPerson))
        .map(toDomain)
    }

  /** List all contacts for the authenticated user (Google Contacts directory). */
  override def listContacts(
    userId:     UserId,
    maxResults: Int,
  ): IO[JorlanError, List[GoogleContact]] =
    withClient(userId) { svc =>
      val result = svc
        .people()
        .connections()
        .nn
        .list("people/me")
        .nn
        .setPageSize(maxResults)
        .setPersonFields(personFields)
        .nn
        .execute()
        .nn
      Option(result.getConnections)
        .map(_.asScala.toList).getOrElse(Nil)
        .map(toDomain)
    }

  /** Retrieve a single contact by People API resource name (e.g. `people/c1234567890`). */
  override def getContact(
    userId:       UserId,
    resourceName: String,
  ): IO[JorlanError, Option[GoogleContact]] =
    withClient(userId) { svc =>
      try
        Option(
          svc
            .people()
            .get(resourceName)
            .nn
            .setPersonFields(personFields)
            .nn
            .execute(),
        ).map(toDomain)
      catch {
        case e: com.google.api.client.googleapis.json.GoogleJsonResponseException
            if e.getStatusCode == 404 =>
          None
      }
    }

}

object GoogleContactsProvider {

  def apply(credentials: OAuthCredentialService): IO[JorlanError, GoogleContactsProvider] =
    for {
      transport <- ZIO
        .attemptBlocking(GoogleNetHttpTransport.newTrustedTransport().nn)
        .mapError(e => JorlanError(s"Failed to initialize People transport: ${e.getMessage}", Some(e)))
      jsonFactory = GsonFactory.getDefaultInstance.nn
      cache <- Ref.make(Map.empty[UserId, (String, PeopleService)])
    } yield new GoogleContactsProvider(credentials, transport, jsonFactory, cache)

}
