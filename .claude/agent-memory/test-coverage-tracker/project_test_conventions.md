---
name: project-test-conventions
description: Test frameworks, file locations, integration vs unit split for Jorlan
metadata:
  type: project
---

## Test Frameworks
- ZIO Test throughout (ZIOSpecDefault)
- Testcontainers (testcontainers-scala-mariadb) for integration tests

## File Locations
- Unit tests: `/home/rleibman/projects/jorlan/server/src/test/scala/jorlan/`
- Integration tests: `/home/rleibman/projects/jorlan/integration/src/test/scala/jorlan/db/`

## Existing Test Files
Unit: DomainSpec, CodecsSpec, ErrorsSpec, service/EventLogServiceSpec, service/TestFixtures
Integration: RepositorySpec, SortingAndSortingSpec, SchedulerRepositorySpec, ArtifactRepositorySpec, PermissionRepositorySpec, EventLogServiceIntegrationSpec, JorlanContainer, TestFixtures

## Key Infrastructure
- `JorlanContainer` provides `repositoryLayer` wiring all ZIO repos via Testcontainers MariaDB
- All integration specs use `@@ TestAspect.sequential` and `.provideLayerShared(JorlanContainer.repositoryLayer)`
- `TestFixtures` in both unit and integration modules provides domain objects and event builders

## Coverage Tool
Scoverage XML at: `/home/rleibman/projects/jorlan/target/scala-3.8.3/scoverage-report/scoverage.xml`
