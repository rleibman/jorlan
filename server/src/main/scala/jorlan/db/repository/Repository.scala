/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.db.repository

import jorlan.*
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

}

extension (settings: ZIOServerSettingsRepository) {

  /** Reads the `initialized` flag from `server_settings`. Returns `false` if the key is absent or not a JSON boolean.
    */
  def isServerInitialized: RepositoryTask[Boolean] =
    settings.get(ZIOServerSettingsRepository.InitializedKey).map {
      case Some(Json.Bool(v)) => v
      case _                  => false
    }

  /** Reads and decodes the server personality. Falls back to [[Personality.default]] if the key is absent or the JSON
    * cannot be decoded, logging a warning in the latter case.
    */
  def getPersonality: RepositoryTask[Personality] =
    settings.get(ZIOServerSettingsRepository.PersonalityKey).flatMap {
      case Some(json) =>
        json.as[Personality] match {
          case Right(p)  => ZIO.succeed(p)
          case Left(err) =>
            ZIO.logWarning(
              s"Corrupt personality in server_settings (key=${ZIOServerSettingsRepository.PersonalityKey}): $err. Falling back to default.",
            ) *> ZIO.succeed(Personality.default)
        }
      case None => ZIO.succeed(Personality.default)
    }

  /** Encodes and persists the given personality. Never fails (encoding errors die). */
  def setPersonality(p: Personality): RepositoryTask[Unit] =
    ZIO
      .fromEither(p.toJsonAST.left.map(RuntimeException(_)))
      .flatMap(json => settings.set(ZIOServerSettingsRepository.PersonalityKey, json))
      .mapError(RepositoryError.apply)

}

type ZIOExternalCredentialRepository = ExternalCredentialRepository[RepositoryTask]

type ZIOServerInfoRepository = ServerInfoRepository[RepositoryTask]

type ZIOSkillIndexRepository = SkillIndexRepository[RepositoryTask]

type ZIORepositories = Repositories[RepositoryTask]
