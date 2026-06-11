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
          val segments = path.segments
          val requestedFile =
            if (segments.isEmpty || segments == Chunk(""))
              new File(root, "index.html")
            else
              new File(root, segments.mkString("/"))

          val fileToServe =
            if (requestedFile.exists() && requestedFile.isFile) requestedFile
            else new File(root, "index.html")

          ZIO.scoped(Handler.fromFile(fileToServe).orDie.apply(req))
      },
    )
  }

}
