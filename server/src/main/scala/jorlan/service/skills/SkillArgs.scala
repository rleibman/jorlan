/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan.service.skills

import jorlan.ChannelType
import zio.json.ast.Json

/** Shared JSON argument extractors used by all built-in [[jorlan.connector.Skill]] implementations. */
private[service] object SkillArgs {

  def str(
    args: Json,
    key:  String,
  ): Option[String] =
    args match {
      case Json.Obj(fields) => fields.collectFirst { case (`key`, Json.Str(v)) => v }
      case _                => None
    }

  def strList(
    args: Json,
    key:  String,
  ): List[String] =
    args match {
      case Json.Obj(fields) =>
        fields
          .collectFirst { case (`key`, Json.Arr(elems)) => elems.collect { case Json.Str(s) => s }.toList }
          .getOrElse(Nil)
      case _ => Nil
    }

  def int(
    args: Json,
    key:  String,
  ): Option[Int] =
    args match {
      case Json.Obj(fields) => fields.collectFirst { case (`key`, Json.Num(n)) => n.intValue }
      case _                => None
    }

  def parseChannelType(s: String): Option[ChannelType] =
    ChannelType.values.find(_.toString.equalsIgnoreCase(s))

}
