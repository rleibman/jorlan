/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan.db.repository

import jorlan.*
import jorlan.domain.Personality
import zio.*
import zio.json.*
import zio.json.ast.Json

/** Effect alias used by all ZIO repository implementations: `IO` with `RepositoryError`. */
type RepositoryTask[A] = IO[RepositoryError, A]

/** ZIO-specific repository type — fixes `F = RepositoryTask` for [[UserRepository]]. */
type ZIOUserRepository = UserRepository[RepositoryTask]

/** ZIO-specific repository type — fixes `F = RepositoryTask` for [[AgentRepository]]. */

type ZIOAgentRepository = AgentRepository[RepositoryTask]

/** ZIO-specific repository type — fixes `F = RepositoryTask` for [[ConversationRepository]]. */
type ZIOConversationRepository = ConversationRepository[RepositoryTask]

/** ZIO-specific repository type — fixes `F = RepositoryTask` for [[SkillRepository]]. */
type ZIOSkillRepository = SkillRepository[RepositoryTask]

/** ZIO-specific repository type — fixes `F = RepositoryTask` for [[MemoryRepository]]. */
type ZIOMemoryRepository = MemoryRepository[RepositoryTask]

/** ZIO-specific repository type — fixes `F = RepositoryTask` for [[EventLogRepository]]. */
type ZIOEventLogRepository = EventLogRepository[RepositoryTask]

/** ZIO-specific repository type — fixes `F = RepositoryTask` for [[SchedulerRepository]]. */
type ZIOSchedulerRepository = SchedulerRepository[RepositoryTask]

/** ZIO-specific repository type — fixes `F = RepositoryTask` for [[ArtifactRepository]]. */
type ZIOArtifactRepository = ArtifactRepository[RepositoryTask]

/** ZIO-specific repository type — fixes `F = RepositoryTask` for [[PermissionRepository]]. */
type ZIOPermissionRepository = PermissionRepository[RepositoryTask]

type ZIOServerSettingsRepository = ServerSettingsRepository[RepositoryTask]

object ZIOServerSettingsRepository {

  /** Key whose value is a JSON boolean (`true`/`false`) indicating whether first-run initialization has completed. */
  val InitializedKey = "initialized"

  /** Key whose value is a JSON string holding the human-readable server name set during initialization. */
  val ServerNameKey = "serverName"

  /** Key whose value is a JSON object encoding the [[Personality]] for this server installation. */
  val PersonalityKey = "personality"

  /** Reads the `initialized` flag from `server_settings`. Returns `false` if the key is absent or not a JSON boolean.
    */
  // TODO I despise this pattern violently. What's wrong with ZIO.serviceWithZIO[ZIORepositories](_.settings.getBoolean(InitializedKey)), it should not be in the companion object!
  def isServerInitialized: ZIO[ZIORepositories, RepositoryError, Boolean] =
    ZIO.serviceWithZIO[ZIORepositories](_.setting.get(InitializedKey)).map {
      case Some(Json.Bool(v)) => v
      case _                  => false
    }

  /** Reads and decodes the server personality. Falls back to [[Personality.default]] if the key is absent or the JSON
    * cannot be decoded, logging a warning in the latter case.
    */
  // TODO I despise this pattern violently. What's wrong with ZIO.serviceWithZIO[ZIORepositories](_.settings.getBoolean(InitializedKey)), it should not be in the companion object!
  def getPersonality: ZIO[ZIORepositories, RepositoryError, Personality] =
    ZIO.serviceWithZIO[ZIORepositories](_.setting.get(PersonalityKey)).flatMap {
      case Some(json) =>
        json.as[Personality] match {
          case Right(p)  => ZIO.succeed(p)
          case Left(err) =>
            ZIO.logWarning(
              s"Corrupt personality in server_settings (key=$PersonalityKey): $err. Falling back to default.",
            ) *> ZIO.succeed(Personality.default)
        }
      case None => ZIO.succeed(Personality.default)
    }

  /** Encodes and persists the given personality. Never fails (encoding errors die). */
  // TODO I despise this pattern violently. What's wrong with ZIO.serviceWithZIO[ZIORepositories](_.settings.getBoolean(InitializedKey)), it should not be in the companion object!
  def setPersonality(p: Personality): ZIO[ZIORepositories, RepositoryError, Unit] =
    ZIO
      .fromEither(p.toJsonAST.left.map(RuntimeException(_)))
      .orDie
      .flatMap(json => ZIO.serviceWithZIO[ZIORepositories](_.setting.set(PersonalityKey, json)))

}

type ZIORepositories = Repositories[RepositoryTask]
