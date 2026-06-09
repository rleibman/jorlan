/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan.db

import jorlan.*
import jorlan.db.TestFixtures.*
import jorlan.db.repository.*
import jorlan.domain.*
import zio.*
import zio.http.MediaType
import zio.test.*

import java.net.URI

object ArtifactRepositorySpec extends ZIOSpec[ZIORepositories] {

  override val boostrap: ZLayer[Any, Any, ZIORepositories] = JorlanContainer.repositoryLayer

  private val pdfMime: MediaType = MediaType.application.pdf
  private val txtMime: MediaType = MediaType.text.plain

  override def spec: Spec[ZIORepositories & TestEnvironment & Scope, Any] =
    suite("ArtifactRepository")(
      test("upsert and retrieve an artifact") {
        for {
          userRepo <- ZIO.serviceWith[ZIORepositories](_.user)
          repo     <- ZIO.serviceWith[ZIORepositories](_.artifact)
          owner    <- userRepo.upsert(User(UserId.empty, "ArtifactOwner", "ArtifactOwner@test.local", T0, T0))
          ws       <- repo.upsertWorkspace(
            Workspace(WorkspaceId.empty, owner.id, "my-ws", Some("desc"), T0, T0),
          )
          artifact = Artifact(
            ArtifactId.empty,
            Some(ws.id),
            None,
            "report.pdf",
            pdfMime,
            1024L,
            URI.create("file:///tmp/report.pdf"),
            None,
            T0,
          )
          saved   <- repo.upsert(artifact)
          fetched <- repo.getById(saved.id)
        } yield assertTrue(
          saved.id.value > 0L,
          fetched.isDefined,
          fetched.exists(_.name == "report.pdf"),
        )
      },
      test("search artifacts by workspace") {
        for {
          userRepo <- ZIO.serviceWith[ZIORepositories](_.user)
          repo     <- ZIO.serviceWith[ZIORepositories](_.artifact)
          owner    <- userRepo.upsert(User(UserId.empty, "ArtifactOwner2", "ArtifactOwner2@test.local", T0, T0))
          ws       <- repo.upsertWorkspace(Workspace(WorkspaceId.empty, owner.id, "ws2", None, T0, T0))
          _        <- repo.upsert(
            Artifact(
              ArtifactId.empty,
              Some(ws.id),
              None,
              "a.txt",
              txtMime,
              100L,
              URI.create("file:///tmp/a.txt"),
              None,
              T0,
            ),
          )
          _ <- repo.upsert(
            Artifact(
              ArtifactId.empty,
              Some(ws.id),
              None,
              "b.txt",
              txtMime,
              200L,
              URI.create("file:///tmp/b.txt"),
              None,
              T0,
            ),
          )
          results <- repo.search(ArtifactSearch(workspaceId = ws.id, pageSize = 20))
        } yield assertTrue(results.length >= 2)
      },
      test("search artifacts sorted by name asc") {
        for {
          userRepo <- ZIO.serviceWith[ZIORepositories](_.user)
          repo     <- ZIO.serviceWith[ZIORepositories](_.artifact)
          owner    <- userRepo.upsert(User(UserId.empty, "ArtifactOwner3", "ArtifactOwner3@test.local", T0, T0))
          ws       <- repo.upsertWorkspace(Workspace(WorkspaceId.empty, owner.id, "ws3", None, T0, T0))
          _        <- repo.upsert(
            Artifact(
              ArtifactId.empty,
              Some(ws.id),
              None,
              "z.pdf",
              pdfMime,
              1L,
              URI.create("file:///tmp/z.pdf"),
              None,
              T0,
            ),
          )
          _ <- repo.upsert(
            Artifact(
              ArtifactId.empty,
              Some(ws.id),
              None,
              "a.pdf",
              pdfMime,
              1L,
              URI.create("file:///tmp/a.pdf"),
              None,
              T0,
            ),
          )
          sorted <- repo.search(
            ArtifactSearch(
              workspaceId = ws.id,
              pageSize = 20,
              sorts = Some(Sort(ArtifactOrder.Name, OrderDirection.Asc)),
            ),
          )
        } yield assertTrue(sorted.map(_.name) == sorted.map(_.name).sorted)
      },
      test("search artifacts sorted by name desc") {
        for {
          userRepo <- ZIO.serviceWith[ZIORepositories](_.user)
          repo     <- ZIO.serviceWith[ZIORepositories](_.artifact)
          owner    <- userRepo.upsert(User(UserId.empty, "ArtifactOwner4", "ArtifactOwner4@test.local", T0, T0))
          ws       <- repo.upsertWorkspace(Workspace(WorkspaceId.empty, owner.id, "ws4", None, T0, T0))
          _        <- repo.upsert(
            Artifact(
              ArtifactId.empty,
              Some(ws.id),
              None,
              "c.txt",
              txtMime,
              1L,
              URI.create("file:///tmp/c.txt"),
              None,
              T0,
            ),
          )
          _ <- repo.upsert(
            Artifact(
              ArtifactId.empty,
              Some(ws.id),
              None,
              "d.txt",
              txtMime,
              1L,
              URI.create("file:///tmp/d.txt"),
              None,
              T0,
            ),
          )
          sorted <- repo.search(
            ArtifactSearch(
              workspaceId = ws.id,
              pageSize = 20,
              sorts = Some(Sort(ArtifactOrder.Name, OrderDirection.Desc)),
            ),
          )
        } yield assertTrue(sorted.map(_.name) == sorted.map(_.name).sorted.reverse)
      },
      test("search artifacts sorted by id desc") {
        for {
          userRepo <- ZIO.serviceWith[ZIORepositories](_.user)
          repo     <- ZIO.serviceWith[ZIORepositories](_.artifact)
          owner    <- userRepo.upsert(User(UserId.empty, "ArtifactOwner5", "ArtifactOwner5@test.local", T0, T0))
          ws       <- repo.upsertWorkspace(Workspace(WorkspaceId.empty, owner.id, "ws5", None, T0, T0))
          _        <- repo.upsert(
            Artifact(
              ArtifactId.empty,
              Some(ws.id),
              None,
              "e.txt",
              txtMime,
              1L,
              URI.create("file:///tmp/e.txt"),
              None,
              T0,
            ),
          )
          _ <- repo.upsert(
            Artifact(
              ArtifactId.empty,
              Some(ws.id),
              None,
              "f.txt",
              txtMime,
              1L,
              URI.create("file:///tmp/f.txt"),
              None,
              T0,
            ),
          )
          sorted <- repo.search(
            ArtifactSearch(
              workspaceId = ws.id,
              pageSize = 20,
              sorts = Some(Sort(ArtifactOrder.Id, OrderDirection.Desc)),
            ),
          )
        } yield assertTrue(sorted.map(_.id.value) == sorted.map(_.id.value).sorted.reverse)
      },
      test("search artifacts sorted by createdAt asc and desc") {
        for {
          userRepo <- ZIO.serviceWith[ZIORepositories](_.user)
          repo     <- ZIO.serviceWith[ZIORepositories](_.artifact)
          owner    <- userRepo.upsert(User(UserId.empty, "ArtifactOwner6", "ArtifactOwner6@test.local", T0, T0))
          ws       <- repo.upsertWorkspace(Workspace(WorkspaceId.empty, owner.id, "ws6", None, T0, T0))
          _        <- repo.upsert(
            Artifact(
              ArtifactId.empty,
              Some(ws.id),
              None,
              "g1.txt",
              txtMime,
              1L,
              URI.create("file:///tmp/g1.txt"),
              None,
              T0.minusSeconds(10),
            ),
          )
          _ <- repo.upsert(
            Artifact(
              ArtifactId.empty,
              Some(ws.id),
              None,
              "g2.txt",
              txtMime,
              1L,
              URI.create("file:///tmp/g2.txt"),
              None,
              T0.plusSeconds(10),
            ),
          )
          asc <- repo.search(
            ArtifactSearch(
              workspaceId = ws.id,
              pageSize = 20,
              sorts = Some(Sort(ArtifactOrder.CreatedAt, OrderDirection.Asc)),
            ),
          )
          desc <- repo.search(
            ArtifactSearch(
              workspaceId = ws.id,
              pageSize = 20,
              sorts = Some(Sort(ArtifactOrder.CreatedAt, OrderDirection.Desc)),
            ),
          )
        } yield assertTrue(
          asc.map(_.createdAt) == asc.map(_.createdAt).sorted,
          desc.map(_.createdAt) == desc.map(_.createdAt).sorted.reverse,
        )
      },
      test("delete removes artifact") {
        for {
          userRepo <- ZIO.serviceWith[ZIORepositories](_.user)
          repo     <- ZIO.serviceWith[ZIORepositories](_.artifact)
          owner    <- userRepo.upsert(User(UserId.empty, "ArtifactOwner7", "ArtifactOwner7@test.local", T0, T0))
          ws       <- repo.upsertWorkspace(Workspace(WorkspaceId.empty, owner.id, "ws7", None, T0, T0))
          a        <- repo.upsert(
            Artifact(
              ArtifactId.empty,
              Some(ws.id),
              None,
              "del.txt",
              txtMime,
              1L,
              URI.create("file:///tmp/del.txt"),
              None,
              T0,
            ),
          )
          count   <- repo.delete(a.id)
          fetched <- repo.getById(a.id)
        } yield assertTrue(count == 1L, fetched.isEmpty)
      },
      test("upsert workspace and retrieve it") {
        for {
          userRepo <- ZIO.serviceWith[ZIORepositories](_.user)
          repo     <- ZIO.serviceWith[ZIORepositories](_.artifact)
          owner    <- userRepo.upsert(User(UserId.empty, "WsOwner1", "WsOwner1@test.local", T0, T0))
          ws <- repo.upsertWorkspace(Workspace(WorkspaceId.empty, owner.id, "workspace-x", Some("A workspace"), T0, T0))
          fetched <- repo.getWorkspace(ws.id)
        } yield assertTrue(
          ws.id.value > 0L,
          fetched.isDefined,
          fetched.exists(_.name == "workspace-x"),
        )
      },
      test("upsert workspace updates mutable fields") {
        for {
          userRepo <- ZIO.serviceWith[ZIORepositories](_.user)
          repo     <- ZIO.serviceWith[ZIORepositories](_.artifact)
          owner    <- userRepo.upsert(User(UserId.empty, "WsOwner2", "WsOwner2@test.local", T0, T0))
          ws       <- repo.upsertWorkspace(Workspace(WorkspaceId.empty, owner.id, "upd-ws", None, T0, T0))
          updated  <- repo.upsertWorkspace(ws.copy(description = Some("updated"), updatedAt = T0.plusSeconds(1)))
          fetched  <- repo.getWorkspace(ws.id)
        } yield assertTrue(
          updated.id == ws.id,
          fetched.exists(_.description.contains("updated")),
        )
      },
      test("searchWorkspaces by owner") {
        for {
          userRepo <- ZIO.serviceWith[ZIORepositories](_.user)
          repo     <- ZIO.serviceWith[ZIORepositories](_.artifact)
          owner    <- userRepo.upsert(User(UserId.empty, "WsOwner3", "WsOwner3@test.local", T0, T0))
          _        <- repo.upsertWorkspace(Workspace(WorkspaceId.empty, owner.id, "ws-a", None, T0, T0))
          _        <- repo.upsertWorkspace(Workspace(WorkspaceId.empty, owner.id, "ws-b", None, T0, T0))
          results  <- repo.searchWorkspaces(WorkspaceSearch(ownerId = owner.id, pageSize = 20))
        } yield assertTrue(results.length >= 2, results.forall(_.ownerId == owner.id))
      },
      test("searchWorkspaces sorted by name asc and desc") {
        for {
          userRepo <- ZIO.serviceWith[ZIORepositories](_.user)
          repo     <- ZIO.serviceWith[ZIORepositories](_.artifact)
          owner    <- userRepo.upsert(User(UserId.empty, "WsOwner4", "WsOwner4@test.local", T0, T0))
          _        <- repo.upsertWorkspace(Workspace(WorkspaceId.empty, owner.id, "bravo", None, T0, T0))
          _        <- repo.upsertWorkspace(Workspace(WorkspaceId.empty, owner.id, "alpha", None, T0, T0))
          asc      <- repo.searchWorkspaces(
            WorkspaceSearch(
              ownerId = owner.id,
              pageSize = 20,
              sorts = Some(Sort(WorkspaceOrder.Name, OrderDirection.Asc)),
            ),
          )
          desc <- repo.searchWorkspaces(
            WorkspaceSearch(
              ownerId = owner.id,
              pageSize = 20,
              sorts = Some(Sort(WorkspaceOrder.Name, OrderDirection.Desc)),
            ),
          )
        } yield assertTrue(
          asc.map(_.name) == asc.map(_.name).sorted,
          desc.map(_.name) == desc.map(_.name).sorted.reverse,
        )
      },
      test("searchWorkspaces sorted by id desc and createdAt asc/desc") {
        for {
          userRepo <- ZIO.serviceWith[ZIORepositories](_.user)
          repo     <- ZIO.serviceWith[ZIORepositories](_.artifact)
          owner    <- userRepo.upsert(User(UserId.empty, "WsOwner5", "WsOwner5@test.local", T0, T0))
          _ <- repo.upsertWorkspace(Workspace(WorkspaceId.empty, owner.id, "ws-sort1", None, T0.minusSeconds(5), T0))
          _ <- repo.upsertWorkspace(Workspace(WorkspaceId.empty, owner.id, "ws-sort2", None, T0.plusSeconds(5), T0))
          idDesc <- repo.searchWorkspaces(
            WorkspaceSearch(
              ownerId = owner.id,
              pageSize = 20,
              sorts = Some(Sort(WorkspaceOrder.Id, OrderDirection.Desc)),
            ),
          )
          createdAsc <- repo.searchWorkspaces(
            WorkspaceSearch(
              ownerId = owner.id,
              pageSize = 20,
              sorts = Some(Sort(WorkspaceOrder.CreatedAt, OrderDirection.Asc)),
            ),
          )
          createdDesc <- repo.searchWorkspaces(
            WorkspaceSearch(
              ownerId = owner.id,
              pageSize = 20,
              sorts = Some(Sort(WorkspaceOrder.CreatedAt, OrderDirection.Desc)),
            ),
          )
        } yield assertTrue(
          idDesc.map(_.id.value) == idDesc.map(_.id.value).sorted.reverse,
          createdAsc.map(_.createdAt) == createdAsc.map(_.createdAt).sorted,
          createdDesc.map(_.createdAt) == createdDesc.map(_.createdAt).sorted.reverse,
        )
      },
    ) @@ TestAspect.sequential

}
