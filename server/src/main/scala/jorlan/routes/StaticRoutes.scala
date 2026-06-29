/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.routes

import auth.*
import jorlan.*
import jorlan.routes.StaticRoutes.file
import zio.*
import zio.http.*
import zio.stream.ZStream

import java.nio.file.{Files, Paths as JPaths}

object StaticRoutes extends AppRoutes[ConfigurationService, Any, JorlanError] {

  private def file(
    fileName: String,
  ): IO[JorlanError, java.io.File] = {
    JPaths.get(fileName) match {
      case path: java.nio.file.Path if !Files.exists(path) =>
        ZIO.fail(NotFoundError(path, s"File not found: $fileName"))
      case path: java.nio.file.Path => ZIO.succeed(path.toFile.nn)
      case null => ZIO.fail(JorlanError(s"HttpError.InternalServerError(Could not find file $fileName))"))
    }
  }

  private def getHeaders(path: String): Headers = {
    // Determine content type
    val contentType = path match {
      case p if p.endsWith(".svg")    => "image/svg+xml"
      case p if p.endsWith(".png")    => "image/png"
      case p if p.endsWith(".jpg")    => "image/jpeg"
      case p if p.endsWith(".jpeg")   => "image/jpeg"
      case p if p.endsWith(".gif")    => "image/gif"
      case p if p.endsWith(".webp")   => "image/webp"
      case p if p.endsWith(".woff")   => "font/woff"
      case p if p.endsWith(".woff2")  => "font/woff2"
      case p if p.endsWith(".ttf")    => "font/ttf"
      case p if p.endsWith(".eot")    => "application/vnd.ms-fontobject"
      case p if p.endsWith(".mp3")    => "audio/mpeg"
      case p if p.endsWith(".wav")    => "audio/wav"
      case p if p.endsWith(".ogg")    => "audio/ogg"
      case p if p.endsWith(".m4a")    => "audio/mp4"
      case p if p.endsWith(".aac")    => "audio/aac"
      case p if p.endsWith(".js")     => "application/javascript"
      case p if p.endsWith(".js.map") => "application/json"
      case p if p.endsWith(".css")    => "text/css"
      case p if p.endsWith(".html")   => "text/html"
      case _                          => "application/octet-stream"
    }

    val isImage = path.endsWith(".png") || path.endsWith(".jpg") || path.endsWith(".jpeg") ||
      path.endsWith(".svg") || path.endsWith(".gif") || path.endsWith(".webp")
    val isFont = path.endsWith(".woff") || path.endsWith(".woff2") || path.endsWith(".ttf") || path.endsWith(".eot")
    val isSound = path.endsWith(".mp3") || path.endsWith(".wav") || path.endsWith(".ogg") ||
      path.endsWith(".m4a") || path.endsWith(".aac")
    val isJs = path.endsWith(".js") || path.endsWith(".js.map")
    val isCss = path.endsWith(".css")

    if (isImage || isFont || isSound) {
      // Cache images, fonts, and sounds for 1 year (they rarely change)
      // public: can be cached by browsers and CDNs
      // immutable: tells browser the file will never change at this URL
      Headers(
        Header.ContentType(MediaType.parseCustomMediaType(contentType).get),
        Header.Custom("Cache-Control", "public, max-age=31536000, immutable"),
      )
    } else if (isJs || isCss) {
      // Cache JS and CSS for 1 day (may change with deployments)
      Headers(
        Header.ContentType(MediaType.parseCustomMediaType(contentType).get),
        Header.Custom("Cache-Control", "public, max-age=86400"),
      )
    } else {
      // HTML and other files: cache for 5 minutes
      Headers(
        Header.ContentType(MediaType.parseCustomMediaType(contentType).get),
        Header.Custom("Cache-Control", "public, max-age=300, must-revalidate"),
      )
    }
  }

  private def telegramLoginHtml(
    botUsername: String,
    redirectUri: String,
  ): String =
    s"""<!DOCTYPE html>
       |<html lang="en">
       |<head>
       |  <meta charset="UTF-8">
       |  <meta name="viewport" content="width=device-width, initial-scale=1.0">
       |  <title>Sign in with Telegram — Jorlan</title>
       |  <style>
       |    body { display:flex; align-items:center; justify-content:center; min-height:100vh;
       |           margin:0; font-family:sans-serif; background:#f5f5f5; }
       |    .card { background:#fff; border-radius:8px; padding:2rem; box-shadow:0 2px 8px rgba(0,0,0,.12);
       |            text-align:center; }
       |    h1 { font-size:1.4rem; margin-bottom:1.5rem; }
       |  </style>
       |</head>
       |<body>
       |  <div class="card">
       |    <h1>Sign in with Telegram</h1>
       |    <script
       |      async
       |      src="https://telegram.org/js/telegram-widget.js?22"
       |      data-telegram-login="$botUsername"
       |      data-size="large"
       |      data-radius="4"
       |      data-auth-url="$redirectUri"
       |      data-request-access="write">
       |    </script>
       |    <p style="margin-top:1.5rem;color:#888;font-size:.85rem;">
       |      You will be redirected back to Jorlan after authorizing.
       |    </p>
       |  </div>
       |</body>
       |</html>""".stripMargin

  override def unauth: ZIO[ConfigurationService, JorlanError, Routes[ConfigurationService, JorlanError]] =
    ZIO.succeed(
      Routes(
        Method.GET / "telegram-login" -> handler { (req: Request) =>
          (for {
            config <- ZIO.serviceWithZIO[ConfigurationService](_.appConfig).mapError(e => JorlanError(e.msg, e.cause))
            tgCfg = config.jorlan.telegramLogin
            state = req.url.queryParams.queryParam("state").getOrElse("")
            redirectWithState =
              tgCfg.redirectUri + (if (state.nonEmpty) s"?state=${java.net.URLEncoder.encode(state, "UTF-8")}" else "")
            html = telegramLoginHtml(tgCfg.botUsername, redirectWithState)
          } yield Response.html(html)).catchAll { e =>
            ZIO.succeed(Response(Status.InternalServerError, body = Body.fromString(e.msg)))
          }
        },
        Method.GET / Root -> handler {
          (
            _: Request
          ) =>
            Handler
              .fromFileZIO {
                for {
                  config <- ZIO.serviceWithZIO[ConfigurationService](_.appConfig)
                  staticContentDir = config.jorlan.http.staticContentDir
                  file <- file(s"$staticContentDir/index.html")
                } yield file
              }.mapError(JorlanError(_))
              .map(response => response.updateHeaders(_ => getHeaders("index.html")))
        }.flatten,
        Method.GET / trailing -> handler {
          (
            path: Path,
            _:    Request,
          ) =>
            val somethingElse = path.toString
            // Never serve the SPA for /api/ paths — those are server routes and should 404 if unmatched
            if (somethingElse.startsWith("api/") || somethingElse == "api") {
              Handler
                .fromZIO(
                  ZIO.fail(NotFoundError(java.nio.file.Paths.get(somethingElse), s"No API route: $somethingElse")),
                ).mapError(JorlanError(_))
            } else {
              Handler
                .fromFileZIO {
                  for {
                    config <- ZIO.serviceWithZIO[ConfigurationService](_.appConfig)
                    staticContentDir = config.jorlan.http.staticContentDir
                    // Fall back to index.html for SPA routing when the requested path doesn't exist as a static file
                    result <- file(s"$staticContentDir/$somethingElse")
                      .orElse(file(s"$staticContentDir/index.html"))
                  } yield result
                }.mapError(JorlanError(_))
                .map(response => response.updateHeaders(_ => getHeaders(somethingElse)))
                .contramap[(Path, Request)](_._2)
            }
        },
      ),
    )

}
