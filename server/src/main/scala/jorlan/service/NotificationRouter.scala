/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan.service

import jorlan.*
import jorlan.connector.{ConnectorSkill, InvocationContext}
import jorlan.db.repository.ZIORepositories
import jorlan.*
import jorlan.service.skills.SkillRegistry
import zio.*
import zio.json.ast.Json

/** Routes outbound notifications to the appropriate connector channel.
  *
  * Resolves a [[UserId]] to a preferred [[ChannelIdentity]] (Telegram first) and invokes the connector's send tool
  * directly via [[ConnectorManager]], bypassing the [[SkillRegistry]] to avoid circular dependency.
  */
trait NotificationRouter {

  /** Send a text message to the user's preferred channel (Telegram preferred).
    *
    * @return
    *   `Json.Str("ok")` on success, or `Json.Str("Error: …")` on failure (no exception propagation).
    */
  def notifyUser(
    userId:  UserId,
    message: String,
    ctx:     InvocationContext,
  ): UIO[Json]

  /** Send a text message to a specific channel identity (channelUserId + channelType).
    *
    * @return
    *   `Json.Str("ok")` on success, or `Json.Str("Error: …")` on failure.
    */
  def notifyChannel(
    channelUserId: String,
    channelType:   ChannelType,
    message:       String,
    ctx:           InvocationContext,
  ): UIO[Json]

}

object NotificationRouter {

  val live: URLayer[ZIORepositories & ConnectorManager, NotificationRouter] =
    ZLayer.fromFunction(NotificationRouterImpl(_, _))

  def notifyUser(
    userId:  UserId,
    message: String,
    ctx:     InvocationContext,
  ): URIO[NotificationRouter, Json] =
    ZIO.serviceWithZIO[NotificationRouter](_.notifyUser(userId, message, ctx))

  def notifyChannel(
    channelUserId: String,
    channelType:   ChannelType,
    message:       String,
    ctx:           InvocationContext,
  ): URIO[NotificationRouter, Json] =
    ZIO.serviceWithZIO[NotificationRouter](_.notifyChannel(channelUserId, channelType, message, ctx))

}

private class NotificationRouterImpl(
  repo:             ZIORepositories,
  connectorManager: ConnectorManager,
) extends NotificationRouter {

  private def channelTypeToConnectorType(ct: ChannelType): Option[ConnectorType] =
    ct match {
      case ChannelType.Telegram => Some(ConnectorType.Telegram)
      case ChannelType.Slack    => Some(ConnectorType.Slack)
      case ChannelType.Email    => Some(ConnectorType.Email)
      case _                    => None
    }

  override def notifyUser(
    userId:  UserId,
    message: String,
    ctx:     InvocationContext,
  ): UIO[Json] =
    repo.user
      .getChannelIdentities(userId)
      .mapError(JorlanError(_))
      .flatMap { identities =>
        // Prefer Telegram, fall back to first available channel
        val preferred = identities.find(_.channelType == ChannelType.Telegram).orElse(identities.headOption)
        preferred match {
          case None =>
            ZIO.succeed(Json.Str(s"Error: user ${userId.value} has no registered channel identities"))
          case Some(ci) =>
            notifyChannel(ci.channelUserId, ci.channelType, message, ctx)
        }
      }
      .catchAll { e =>
        ZIO.logWarning(s"NotificationRouter.notifyUser failed: ${e.msg}") *>
          ZIO.succeed(Json.Str(s"Error: ${e.msg}"))
      }

  override def notifyChannel(
    channelUserId: String,
    channelType:   ChannelType,
    message:       String,
    ctx:           InvocationContext,
  ): UIO[Json] = {
    channelTypeToConnectorType(channelType) match {
      case None =>
        ZIO.succeed(Json.Str(s"Error: no connector available for channel type ${channelType.toString}"))
      case Some(connType) =>
        connectorManager.connectorMap.get(connType) match {
          case None =>
            ZIO.succeed(Json.Str(s"Error: no registered connector for ${connType.toString}"))
          case Some(cs) =>
            cs.sendMessageToolName match {
              case None =>
                ZIO.succeed(Json.Str(s"Error: connector ${connType.toString} has no send capability"))
              case Some(toolName) =>
                val args = Json.Obj(
                  "chatId" -> Json.Str(channelUserId),
                  "text"   -> Json.Str(message),
                )
                cs.invoke(ctx, toolName, args)
                  .catchAll { e =>
                    ZIO.logWarning(s"NotificationRouter.notifyChannel[$toolName] error: ${e.msg}") *>
                      ZIO.succeed(Json.Str(s"Error: ${e.msg}"))
                  }
            }
        }
    }
  }

}
