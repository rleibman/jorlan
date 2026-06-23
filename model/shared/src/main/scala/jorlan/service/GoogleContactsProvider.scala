/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.service

import jorlan.*

/** A single contact record returned by the Google People API. */
case class GoogleContact(
  resourceName:  String,
  displayName:   Option[String],
  emails:        List[String],
  phones:        List[String],
  organizations: List[String],
)

/** Abstract Google Contacts backend. `F[_]` is the effect type — typically `IO[JorlanError, *]` in production. The live
  * implementation is [[jorlan.google.GoogleContactsProvider]].
  */
trait GoogleContactsProvider[F[_]] {

  def searchContacts(
    userId:     UserId,
    query:      String,
    maxResults: Int,
  ): F[List[GoogleContact]]

  def listContacts(
    userId:     UserId,
    maxResults: Int,
  ): F[List[GoogleContact]]

  def getContact(
    userId:       UserId,
    resourceName: String,
  ): F[Option[GoogleContact]]

}
