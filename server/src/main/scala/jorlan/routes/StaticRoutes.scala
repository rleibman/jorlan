/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan.routes

import StaticRoutes.file
import auth.*
import jorlan.*
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

  override def unauth: ZIO[ConfigurationService, JorlanError, Routes[ConfigurationService, JorlanError]] =
    ZIO.succeed(
      Routes(
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
            // You might want to restrict the files that could come back, but then again, you may not
            val somethingElse = path.toString
            Handler
              .fromFileZIO {
                for {
                  config <- ZIO.serviceWithZIO[ConfigurationService](_.appConfig)
                  staticContentDir = config.jorlan.http.staticContentDir
                  file <- file(s"$staticContentDir/$somethingElse")
                  cacheHeaders = getHeaders(somethingElse)
                } yield file
              }.mapError(JorlanError(_))
              .map(response => response.updateHeaders(_ => getHeaders(somethingElse)))
              .contramap[(Path, Request)](_._2)
        },
      ),
    )

}
