/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.db.repository

import jorlan.JorlanError

import java.sql.{SQLNonTransientException, SQLTransientException}

/** Typed error returned by all ZIO repository operations.
  */
object RepositoryError {

  /** Constructs a [[RepositoryError]] from a raw `Throwable`, propagating transience from the JDBC exception hierarchy.
    */
  def apply(cause: Throwable): RepositoryError =
    cause match {
      case e: SQLTransientException    => RepositoryError(e.getMessage.nn, Some(cause), isTransient = true)
      case e: SQLNonTransientException => RepositoryError(e.getMessage.nn, Some(cause))
      case _ => RepositoryError(cause.getMessage.nn, Some(cause))
    }

}

case class RepositoryError(
  override val msg:         String = "",
  override val cause:       Option[Throwable] = None,
  override val isTransient: Boolean = false,
) extends JorlanError(msg, cause, isTransient)
