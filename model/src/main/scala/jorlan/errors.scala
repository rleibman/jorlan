/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan

import java.nio.file.Path
import scala.language.unsafeNulls

/** Root error type for all application-level failures.
  *
  * All subsystem errors (`ConfigurationError`, `RepositoryError`, etc.) extend this class, providing a single catch
  * site and unified `isTransient` classification throughout the application.
  */
object JorlanError {

  def apply(cause: Throwable): JorlanError =
    cause match {
      case e: JorlanError => e
      case e => new JorlanError(cause.getMessage, Some(cause))
    }
  def apply(message: String): JorlanError = new JorlanError(message)

  def apply(
    msg:         String,
    cause:       Option[Throwable] = None,
    isTransient: Boolean = false,
  ): JorlanError = new JorlanError(msg, cause, isTransient)

}

class JorlanError(
  val msg:         String,
  val cause:       Option[Throwable] = None,
  val isTransient: Boolean = false,
) extends Exception(msg, cause.orNull) {}

/** Raised when user input fails validation. Routes should return HTTP 400 for this error. */
class ValidationError(
  override val msg: String,
  cause:            Option[Throwable] = None,
) extends JorlanError(msg, cause)

object ValidationError {

  def apply(message: String): ValidationError = new ValidationError(message)

}

class NotFoundError(
  val path:         Path,
  override val msg: String,
  cause:            Option[Throwable] = None,
  isTransient:      Boolean = false,
) extends JorlanError(msg, cause, isTransient)

object NotFoundError {

  def apply(
    path:    Path,
    message: String,
  ): NotFoundError = new NotFoundError(path, message)

  def apply(
    path:        Path,
    msg:         String,
    cause:       Option[Throwable] = None,
    isTransient: Boolean = false,
  ): NotFoundError = new NotFoundError(path, msg, cause, isTransient)

}
