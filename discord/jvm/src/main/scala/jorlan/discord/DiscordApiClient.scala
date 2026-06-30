/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.discord

import jorlan.*
import net.dv8tion.jda.api.{JDA, JDABuilder}
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import net.dv8tion.jda.api.requests.GatewayIntent
import zio.*
import zio.json.ast.Json

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicReference

import scala.language.unsafeNulls

/** Connector-neutral Discord client interface.
  *
  * JDA types do not appear in this trait so that [[DiscordMessageNormalizer]], [[DiscordConnectorSkill]], and tests can
  * operate without a live JDA gateway connection.
  */
trait DiscordApiClient {

  /** Open the JDA gateway connection and block until READY. */
  def connect(): IO[JorlanError, Unit]

  /** Signal shutdown — drains the event queue with a sentinel and shuts down the JDA instance. */
  def disconnect(): UIO[Unit]

  /** Dequeue the next inbound message event.
    *
    * Returns `None` as a shutdown sentinel when [[disconnect]] has been called.
    */
  def nextEvent(): IO[JorlanError, Option[DiscordRawMessage]]

  /** Send a text message to a guild text channel by Snowflake ID. */
  def sendToChannel(
    channelId: String,
    content:   String,
  ): IO[JorlanError, Unit]

  /** Open a DM channel with a user and send a text message. */
  def sendToDm(
    userId:  String,
    content: String,
  ): IO[JorlanError, Unit]

  /** Retrieve the most recent `limit` messages from a channel as raw JSON. */
  def getChannelHistory(
    channelId: String,
    limit:     Int,
  ): IO[JorlanError, List[Json]]

  /** Return metadata for a channel as a JSON object. */
  def getChannelInfo(channelId: String): IO[JorlanError, Json]

}

/** [[DiscordApiClient]] backed by a real JDA gateway connection.
  *
  * JDA blocking calls are wrapped in [[ZIO.blocking]] so they do not monopolise ZIO worker threads. The event queue is
  * a `LinkedBlockingQueue` that the JDA listener writes to from its own thread pool; `nextEvent()` drains it from the
  * ZIO blocking thread pool.
  */
class DiscordApiClientLive(config: DiscordConfig) extends DiscordApiClient {

  private val queue: LinkedBlockingQueue[Either[Unit, DiscordRawMessage]] =
    new LinkedBlockingQueue[Either[Unit, DiscordRawMessage]](1024)

  // AtomicReference is used here because the JDA instance is set from inside ZIO.blocking and read
  // from other ZIO effects. A java.util.concurrent.atomic.AtomicReference is safe for this pattern.
  private val jdaRef: AtomicReference[JDA | Null] = new AtomicReference[JDA | Null](null)

  override def connect(): IO[JorlanError, Unit] =
    ZIO
      .blocking {
        ZIO
          .attempt {
            val listener = new ListenerAdapter {
              override def onMessageReceived(event: MessageReceivedEvent): Unit = {
                val msg = event.getMessage
                val author = msg.getAuthor
                val guildId: Option[String] =
                  if (event.isFromGuild) Some(event.getGuild.getId) else None
                val isMention = msg.getMentions.isMentioned(event.getJDA.getSelfUser)
                val raw = DiscordRawMessage(
                  messageId = msg.getId,
                  authorId = author.getId,
                  authorName = author.getName,
                  channelId = event.getChannel.getId,
                  guildId = guildId,
                  isBot = author.isBot,
                  content = msg.getContentRaw,
                  isMention = isMention,
                  receivedAt = java.time.Instant.now(),
                )
                if (!queue.offer(Right(raw))) {
                  // Queue full — drop the oldest message to make room (sliding behaviour).
                  val _ = queue.poll()
                  val _ = queue.offer(Right(raw))
                  // TODO never, ever use println in a zio app.
                  java.lang.System.err.println(
                    s"[DiscordApiClient] WARN: inbound queue full; oldest message dropped (channel=${raw.channelId})",
                  )
                }
              }
            }
            JDABuilder
              .createDefault(config.botToken)
              .enableIntents(GatewayIntent.GUILD_MESSAGES, GatewayIntent.DIRECT_MESSAGES, GatewayIntent.MESSAGE_CONTENT)
              .addEventListeners(listener)
              .build()
              .awaitReady()
          }.mapError(e => JorlanError(s"Discord connect failed: ${e.getMessage}"))
      }.flatMap { jda =>
        ZIO.succeed(jdaRef.set(jda))
      }

  override def disconnect(): UIO[Unit] =
    ZIO
      .succeed {
        queue.clear()
        val _ = queue.offer(Left(()))
        jdaRef.getAndSet(null)
      }
      .flatMap { jda =>
        ZIO.blocking(ZIO.attempt(jda.nn.shutdown())).ignore.unless(jda == null).unit
      }

  override def nextEvent(): IO[JorlanError, Option[DiscordRawMessage]] =
    ZIO
      .blocking {
        ZIO.attempt(queue.take()).mapError(e => JorlanError(s"Discord event queue interrupted: ${e.getMessage}"))
      }.map {
        case Right(msg) => Some(msg)
        case Left(_)    => None
      }

  private def withJda[A](f: JDA => IO[JorlanError, A]): IO[JorlanError, A] = {
    val jda = jdaRef.get()
    if (jda == null) ZIO.fail(JorlanError("Discord: not connected"))
    else f(jda.nn)
  }

  private def withChannel[A](channelId: String)(f: TextChannel => IO[JorlanError, A]): IO[JorlanError, A] =
    withJda { jda =>
      val channel: TextChannel | Null = jda.getTextChannelById(channelId)
      if (channel == null) ZIO.fail(JorlanError(s"Discord: text channel $channelId not found"))
      else f(channel.nn)
    }

  override def sendToChannel(
    channelId: String,
    content:   String,
  ): IO[JorlanError, Unit] =
    withChannel(channelId) { channel =>
      ZIO.blocking {
        ZIO
          .attempt(channel.sendMessage(content).complete())
          .mapError(e => JorlanError(s"Discord sendToChannel failed: ${e.getMessage}"))
          .unit
      }
    }

  override def sendToDm(
    userId:  String,
    content: String,
  ): IO[JorlanError, Unit] =
    withJda { jda =>
      ZIO.blocking {
        ZIO
          .attempt {
            val user = jda.retrieveUserById(userId).complete()
            val channel = user.nn.openPrivateChannel().complete()
            val _ = channel.nn.sendMessage(content).complete()
          }
          .mapError(e => JorlanError(s"Discord sendToDm failed: ${e.getMessage}"))
      }
    }

  override def getChannelHistory(
    channelId: String,
    limit:     Int,
  ): IO[JorlanError, List[Json]] =
    withChannel(channelId) { channel =>
      ZIO.blocking {
        ZIO
          .attempt {
            val history = channel.getHistory()
            val msgs = history.retrievePast(limit).complete()
            import scala.jdk.CollectionConverters.*
            msgs.nn.asScala.toList.map { m =>
              Json.Obj(
                "id"        -> Json.Str(m.nn.getId),
                "authorId"  -> Json.Str(m.nn.getAuthor.getId),
                "author"    -> Json.Str(m.nn.getAuthor.getName),
                "content"   -> Json.Str(m.nn.getContentRaw),
                "timestamp" -> Json.Str(m.nn.getTimeCreated.toInstant.toString),
              )
            }
          }
          .mapError(e => JorlanError(s"Discord getChannelHistory failed: ${e.getMessage}"))
      }
    }

  override def getChannelInfo(channelId: String): IO[JorlanError, Json] =
    withChannel(channelId) { channel =>
      ZIO.succeed {
        val guildId = channel.getGuild.getId
        val gName = channel.getGuild.getName
        Json.Obj(
          "id"        -> Json.Str(channel.getId),
          "name"      -> Json.Str(channel.getName),
          "guildId"   -> Json.Str(guildId),
          "guildName" -> Json.Str(gName),
          "type"      -> Json.Str("TEXT"),
        )
      }
    }

}
