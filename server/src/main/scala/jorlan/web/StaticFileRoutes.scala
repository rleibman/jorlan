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

import java.io.File

/** Serves the Scala.js web frontend as static files.
  *
  * All GET requests not handled by the API or auth routes fall through to this handler. Unknown paths fall back to
  * `index.html` so that client-side hash routing works without server-side route knowledge.
  */
object StaticFileRoutes {

  def routes(webRoot: String): Routes[Any, Nothing] = {
    val root = new File(webRoot)

    Routes(
      Method.GET / trailing -> handler {
        (
          path: Path,
          req:  Request,
        ) =>
          ZIO
            .attemptBlocking {
              val segments = path.segments
              val candidate =
                if (segments.isEmpty || segments == Chunk(""))
                  new File(root, "index.html")
                else
                  new File(root, segments.mkString("/"))

              // Reject path traversal: canonical path must stay under webRoot
              val rootPath      = root.getCanonicalFile.toPath
              val candidatePath = candidate.getCanonicalFile.toPath
              if (!candidatePath.startsWith(rootPath))
                None // traversal attempt — fall through to index.html
              else if (candidate.exists() && candidate.isFile)
                Some(candidate)
              else
                Some(new File(root, "index.html"))
            }.orDie.flatMap { fileOpt =>
              val fileToServe = fileOpt.getOrElse(new File(root, "index.html"))
              ZIO.scoped(Handler.fromFile(fileToServe).orDie.apply(req)).map { resp =>
                // index.html and non-fingerprinted assets must revalidate; fingerprinted assets can be cached for a year
                val name          = fileToServe.getName
                val fingerprinted = name.matches(""".+\.[0-9a-fA-F]{6,}\..+""")
                if (name == "index.html" || !fingerprinted)
                  resp.addHeader(Header.CacheControl.NoCache)
                else
                  resp.addHeader(Header.CacheControl.MaxAge(31536000))
              }
            }
      },
    )
  }

}
