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
import jorlan.domain.*
import zio.*

/** Application service for user lifecycle management.
  *
  * All state-mutating operations write to the event log so the user lifecycle is fully auditable.
  */
trait UserService {

  def getById(id: UserId):     IO[JorlanError, Option[User]]
  def search(s:   UserSearch): IO[JorlanError, List[User]]
  def createUser(
    displayName: String,
    email:       Option[String],
    actorId:     Option[UserId] = None,
  ): IO[JorlanError, User]
  def updateUser(
    id:          UserId,
    displayName: String,
    email:       Option[String],
    active:      Boolean,
    actorId:     Option[UserId] = None,
  ): IO[JorlanError, User]

  /** Sets (or replaces) the password for `userId`. Called by [[jorlan.init.InitServiceImpl]] after user creation to
    * store the admin credential. Accepts the plain-text password; hashing is delegated to the repository layer.
    */
  def setPassword(
    userId:   UserId,
    password: String,
  ): IO[JorlanError, Unit]

}

object UserService {

  def getById(id: UserId): ZIO[UserService, JorlanError, Option[User]] = ZIO.serviceWithZIO[UserService](_.getById(id))

  def search(s: UserSearch): ZIO[UserService, JorlanError, List[User]] = ZIO.serviceWithZIO[UserService](_.search(s))

  def createUser(
    displayName: String,
    email:       Option[String],
    actorId:     Option[UserId] = None,
  ): ZIO[UserService, JorlanError, User] = ZIO.serviceWithZIO[UserService](_.createUser(displayName, email, actorId))

  def updateUser(
    id:          UserId,
    displayName: String,
    email:       Option[String],
    active:      Boolean,
    actorId:     Option[UserId] = None,
  ): ZIO[UserService, JorlanError, User] =
    ZIO.serviceWithZIO[UserService](_.updateUser(id, displayName, email, active, actorId))

  def setPassword(
    userId:   UserId,
    password: String,
  ): ZIO[UserService, JorlanError, Unit] =
    ZIO.serviceWithZIO[UserService](_.setPassword(userId, password))

}
