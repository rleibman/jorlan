/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.service

import zio.*
import zio.stream.ZStream

/** Keyed pub-sub hub backed by per-subscriber dropping queues.
  *
  * Use `K = Unit` for broadcast (all subscribers receive every event regardless of key).
  *
  * Guarantees:
  *   - `publish` never back-pressures the caller — events are silently dropped when a subscriber's queue is full.
  *   - Each subscriber's queue is cleaned up when its stream terminates (`ZStream.ensuring`).
  *   - Fan-out is parallel (`ZIO.foreachParDiscard`), so a slow subscriber does not stall others.
  *
  * @tparam K
  *   key type used to scope events to a subset of subscribers
  * @tparam V
  *   event type
  */
class KeyedPubSubHub[K, V] private (subs: Ref[Map[K, List[Queue[V]]]]) {

  /** Returns a new stream of events for the given key. Each call creates an independent subscription. */
  def subscribe(key: K): UIO[ZStream[Any, Nothing, V]] =
    for {
      queue <- Queue.dropping[V](256)
      _     <- subs.update { map =>
        val existing = map.getOrElse(key, List.empty)
        map + (key -> (queue :: existing))
      }
    } yield ZStream
      .fromQueue(queue)
      .ensuring(
        queue.shutdown *>
          subs.update { map =>
            val updated = map.getOrElse(key, List.empty).filterNot(_ eq queue)
            if (updated.isEmpty) map - key
            else map + (key -> updated)
          },
      )

  /** Publishes an event to all active subscribers for `key`. */
  def publish(
    key:   K,
    event: V,
  ): UIO[Unit] =
    subs.get.flatMap { map =>
      ZIO.foreachParDiscard(map.getOrElse(key, List.empty))(_.offer(event).unit)
    }

}

object KeyedPubSubHub {

  def make[K, V]: UIO[KeyedPubSubHub[K, V]] =
    Ref.make(Map.empty[K, List[Queue[V]]]).map(new KeyedPubSubHub(_))

}
