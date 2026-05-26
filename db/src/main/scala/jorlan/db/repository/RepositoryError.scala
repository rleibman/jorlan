/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan.db.repository

import jorlan.JorlanError

import java.sql.{SQLNonTransientException, SQLTransientException}

/** Typed error returned by all ZIO repository operations.
  *
  * @param isTransient
  *   `true` when the underlying SQL exception is transient (e.g. deadlock, timeout) and the caller may safely retry;
  *   `false` for permanent failures (e.g. constraint violation).
  */
object RepositoryError {

  /** Constructs a [[RepositoryError]] from a raw `Throwable`, propagating transience from the JDBC exception hierarchy.
    */
  def apply(cause: Throwable): RepositoryError =
    cause match {
      case e: SQLTransientException    => RepositoryError(e.getMessage.nn, Some(cause), isTransient = true)
      case e: SQLNonTransientException => RepositoryError(e.getMessage.nn, Some(cause), isTransient = false)
      case _ => RepositoryError(cause.getMessage.nn, Some(cause))
    }

}

case class RepositoryError(
  override val msg:         String = "",
  override val cause:       Option[Throwable] = None,
  override val isTransient: Boolean = false,
) extends JorlanError(msg, cause, isTransient)