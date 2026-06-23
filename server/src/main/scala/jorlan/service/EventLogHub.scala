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
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.stream.ZStream

/** Pub-sub hub for [[EventLog]] entries.
  *
  * All subscribers receive every event (broadcast). Subscribers get their own bounded Queue; publishing is
  * fire-and-forget (no drop on slow subscribers — offer back-pressures).
  */
class EventLogHub private (subs: Ref[List[Queue[EventLog[Json]]]]) {

  def subscribe: UIO[ZStream[Any, Nothing, EventLog[Json]]] =
    for {
      queue <- Queue.bounded[EventLog[Json]](512)
      _     <- subs.update(queue :: _)
    } yield ZStream
      .fromQueue(queue)
      .ensuring(
        queue.shutdown *>
          subs.update(_.filterNot(_ eq queue)),
      )

  def publish(event: EventLog[Json]): UIO[Unit] =
    subs.get.flatMap(qs => ZIO.foreachParDiscard(qs)(_.offer(event).unit))

  def publishTyped[R: JsonEncoder](event: EventLog[R]): UIO[Unit] = {
    val jsonEvent = EventLog[Json](
      id = event.id,
      eventType = event.eventType,
      actorId = event.actorId,
      agentId = event.agentId,
      sessionId = event.sessionId,
      resource = event.resource.flatMap(r => summon[JsonEncoder[R]].toJsonAST(r).toOption),
      payloadJson = event.payloadJson,
      occurredAt = event.occurredAt,
    )
    publish(jsonEvent)
  }

}

object EventLogHub {

  val make: UIO[EventLogHub] =
    Ref.make(List.empty[Queue[EventLog[Json]]]).map(EventLogHub(_))

  val live: ULayer[EventLogHub] = ZLayer.fromZIO(make)

}
