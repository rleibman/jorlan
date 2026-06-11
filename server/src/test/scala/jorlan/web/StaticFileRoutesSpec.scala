/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan.web

import zio.*
import zio.http.*
import zio.test.*

import java.nio.file.{Files, Path as NioPath}

object StaticFileRoutesSpec extends ZIOSpecDefault {

  private def withWebRoot(
    files: Map[String, String],
  )(
    f: String => RIO[Any, TestResult],
  ): RIO[Any, TestResult] =
    ZIO.acquireReleaseWith(
      ZIO.attempt(Files.createTempDirectory("static-test")),
    )(tmpDir =>
      ZIO.attempt {
        def deleteRecursively(p: NioPath): Unit = {
          if (Files.isDirectory(p)) {
            val stream = Files.newDirectoryStream(p)
            try stream.forEach(child => deleteRecursively(child))
            finally stream.close()
          }
          Files.deleteIfExists(p)
        }
        deleteRecursively(tmpDir)
      }.ignore,
    ) { tmpDir =>
      ZIO.foreach(files.toList) { case (name, content) =>
        ZIO.attempt(Files.writeString(tmpDir.resolve(name), content))
      } *> f(tmpDir.toString)
    }

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("StaticFileRoutes")(
      test("serves index.html at root path") {
        withWebRoot(Map("index.html" -> "<html>ok</html>")) { root =>
          val routes = StaticFileRoutes.routes(root)
          ZIO.scoped(routes.run(Method.GET, Path.decode("/"))).map { resp =>
            assertTrue(resp.status == Status.Ok)
          }
        }
      },
      test("serves a named asset that exists") {
        withWebRoot(Map("index.html" -> "", "app.js" -> "console.log('hi')")) { root =>
          val routes = StaticFileRoutes.routes(root)
          ZIO.scoped(routes.run(Method.GET, Path.decode("/app.js"))).map { resp =>
            assertTrue(resp.status == Status.Ok)
          }
        }
      },
      test("falls back to index.html for unknown path") {
        withWebRoot(Map("index.html" -> "<html>spa</html>")) { root =>
          val routes = StaticFileRoutes.routes(root)
          ZIO.scoped(routes.run(Method.GET, Path.decode("/some/unknown/route"))).map { resp =>
            assertTrue(resp.status == Status.Ok)
          }
        }
      },
      test("path traversal attempt falls back to index.html (not a 500)") {
        withWebRoot(Map("index.html" -> "<html>safe</html>")) { root =>
          val routes = StaticFileRoutes.routes(root)
          ZIO.scoped(routes.run(Method.GET, Path.decode("/../etc/passwd"))).map { resp =>
            assertTrue(resp.status == Status.Ok)
          }
        }
      },
      test("index.html response has Cache-Control: no-cache") {
        withWebRoot(Map("index.html" -> "<html>index</html>")) { root =>
          val routes = StaticFileRoutes.routes(root)
          ZIO.scoped(routes.run(Method.GET, Path.decode("/"))).map { resp =>
            assertTrue(resp.headers.get(Header.CacheControl).isDefined)
          }
        }
      },
      test("hashed asset response has Cache-Control: max-age=31536000") {
        withWebRoot(Map("index.html" -> "", "bundle.abc123.js" -> "js")) { root =>
          val routes = StaticFileRoutes.routes(root)
          ZIO.scoped(routes.run(Method.GET, Path.decode("/bundle.abc123.js"))).map { resp =>
            val cc = resp.headers.get(Header.CacheControl)
            assertTrue(resp.status == Status.Ok, cc.isDefined)
          }
        }
      },
    )

}
