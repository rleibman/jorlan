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
import zio.*

/** Effect alias used by all ZIO repository implementations: `IO` with `RepositoryError`. */
type RepositoryTask[A] = IO[RepositoryError, A]

/** ZIO-specific repository trait — fixes `F = RepositoryTask` for [[UserRepository]]. */
trait UserZIORepository extends UserRepository[RepositoryTask]

/** ZIO-specific repository trait — fixes `F = RepositoryTask` for [[AgentRepository]]. */

trait AgentZIORepository extends AgentRepository[RepositoryTask]

/** ZIO-specific repository trait — fixes `F = RepositoryTask` for [[ConversationRepository]]. */
trait ConversationZIORepository extends ConversationRepository[RepositoryTask]

/** ZIO-specific repository trait — fixes `F = RepositoryTask` for [[SkillRepository]]. */
trait SkillZIORepository extends SkillRepository[RepositoryTask]

/** ZIO-specific repository trait — fixes `F = RepositoryTask` for [[MemoryRepository]]. */
trait MemoryZIORepository extends MemoryRepository[RepositoryTask]

/** ZIO-specific repository trait — fixes `F = RepositoryTask` for [[EventLogRepository]]. */
trait EventLogZIORepository extends EventLogRepository[RepositoryTask]

/** ZIO-specific repository trait — fixes `F = RepositoryTask` for [[SchedulerRepository]]. */
trait SchedulerZIORepository extends SchedulerRepository[RepositoryTask]

/** ZIO-specific repository trait — fixes `F = RepositoryTask` for [[ArtifactRepository]]. */
trait ArtifactZIORepository extends ArtifactRepository[RepositoryTask]

/** ZIO-specific repository trait — fixes `F = RepositoryTask` for [[PermissionRepository]]. */
trait PermissionZIORepository extends PermissionRepository[RepositoryTask]
