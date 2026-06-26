/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.service.skills

import jorlan.JorlanError
import jorlan.connector.{Skill, SkillPlugin}
import zio.*
import zio.http.Client
import zio.json.ast.Json

import java.io.File
import java.net.URLClassLoader
import java.util.ServiceLoader
import scala.jdk.CollectionConverters.*

/** Scans a directory for plugin JARs, discovers [[SkillPlugin]] implementations via [[ServiceLoader]], and instantiates
  * each [[Skill]].
  *
  * Uses the current thread's context classloader as parent so all Jorlan / ZIO / zio-http types are shared between the
  * server and the plugin — no duplicate class issues.
  */
object SkillPluginLoader {

  def loadAll(
    pluginsDir: File,
    client:     Client,
    getSetting: String => UIO[Option[Json]],
    setSetting: (String, Json) => UIO[Unit],
  ): IO[JorlanError, List[Skill]] = {
    if (!pluginsDir.exists() || !pluginsDir.isDirectory) {
      ZIO.logInfo(s"Plugin directory '${pluginsDir.getAbsolutePath}' not found — no plugins loaded") *>
        ZIO.succeed(List.empty)
    } else {
      val jars = Option(pluginsDir.listFiles()).toList.flatten.filter(_.getName.endsWith(".jar"))
      if (jars.isEmpty) {
        ZIO.logInfo(s"Plugin directory '${pluginsDir.getAbsolutePath}' is empty — no plugins loaded") *>
          ZIO.succeed(List.empty)
      } else {
        ZIO.logInfo(s"Loading ${jars.size} plugin JAR(s) from '${pluginsDir.getAbsolutePath}'") *>
          ZIO.foreach(jars)(jar => loadJar(jar, client, getSetting, setSetting)).map(_.flatten)
      }
    }
  }

  private def loadJar(
    jar:        File,
    client:     Client,
    getSetting: String => UIO[Option[Json]],
    setSetting: (String, Json) => UIO[Unit],
  ): IO[JorlanError, List[Skill]] = {
    ZIO.acquireReleaseWith(
      ZIO
        .attempt {
          val parent = Thread.currentThread().getContextClassLoader.nn
          new URLClassLoader(Array(jar.toURI.toURL), parent)
        }.mapError(e => JorlanError(s"Failed to open plugin JAR '${jar.getName}'", Some(e))),
    )(_ => ZIO.unit) { cl =>
      ZIO
        .attempt {
          ServiceLoader
            .load(classOf[SkillPlugin], cl)
            .iterator()
            .asScala
            .toList
        }
        .mapError(e => JorlanError(s"ServiceLoader failed for '${jar.getName}'", Some(e)))
        .flatMap { factories =>
          if (factories.isEmpty) {
            ZIO.logWarning(s"Plugin JAR '${jar.getName}' contains no SkillPlugin implementations") *>
              ZIO.succeed(List.empty)
          } else {
            ZIO.foreach(factories) { factory =>
              factory
                .create(client, getSetting, setSetting)
                .tapBoth(
                  e => ZIO.logError(s"Plugin '${jar.getName}' / ${factory.getClass.getName} failed: ${e.msg}"),
                  s => ZIO.logInfo(s"Plugin skill '${s.descriptor.name}' loaded from '${jar.getName}'"),
                )
            }
          }
        }
    }
  }

}
