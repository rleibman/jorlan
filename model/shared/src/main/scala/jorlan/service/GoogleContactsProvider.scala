/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
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
