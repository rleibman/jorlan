/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan.web.pages

import japgolly.scalajs.react.Callback

import scala.util.{Failure, Success, Try}

object PageUtils {

  def onError[A](handler: Option[String] => Callback): Try[A] => Callback = {
    case Failure(ex) => handler(Some(Option(ex.getMessage).getOrElse(ex.getClass.getName)))
    case Success(_)  => Callback.empty
  }

}
