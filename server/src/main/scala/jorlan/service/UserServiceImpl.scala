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
import jorlan.db.repository.UserZIORepository
import jorlan.domain.*
import zio.*

private class UserServiceImpl(
  repo:     UserZIORepository,
  eventLog: EventLogService,
) extends UserService {

  override def getById(id: UserId): IO[JorlanError, Option[User]] = repo.getById(id)

  override def search(s: UserSearch): IO[JorlanError, List[User]] = repo.search(s)

  override def createUser(
    displayName: String,
    email:       Option[String],
    actorId:     Option[UserId] = None,
  ): IO[JorlanError, User] =
    for {
      now  <- Clock.instant
      user <- repo.upsert(User(UserId.empty, displayName, email, now, now))
      _    <- eventLog.log(
        EventLog(
          id = EventLogId.empty,
          eventType = EventType.UserCreated,
          actorId = actorId,
          agentId = None,
          sessionId = None,
          resource = Some(user.id),
          payloadJson = None,
          occurredAt = now,
        ),
      )
    } yield user

  override def updateUser(
    id:          UserId,
    displayName: String,
    email:       Option[String],
    active:      Boolean,
    actorId:     Option[UserId] = None,
  ): IO[JorlanError, User] =
    for {
      now      <- Clock.instant
      existing <- repo.getById(id).someOrFail(JorlanError(s"User ${id.value} not found"))
      user     <- repo.upsert(User(id, displayName, email, existing.createdAt, now, active))
      _        <- eventLog.log(
        EventLog(
          id = EventLogId.empty,
          eventType = EventType.UserUpdated,
          actorId = actorId,
          agentId = None,
          sessionId = None,
          resource = Some(user.id),
          payloadJson = None,
          occurredAt = now,
        ),
      )
    } yield user

}

object UserServiceImpl {

  val live: URLayer[UserZIORepository & EventLogService, UserService] =
    ZLayer.fromFunction(
      (
        repo:     UserZIORepository,
        eventLog: EventLogService,
      ) => new UserServiceImpl(repo, eventLog),
    )

}
