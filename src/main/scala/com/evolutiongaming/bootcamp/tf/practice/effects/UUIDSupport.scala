package com.evolutiongaming.bootcamp.tf.practice.effects

import cats.effect.Sync

import java.util.UUID

trait UUIDSupport[F[_]] {
  def random: F[UUID]
  def read(str: String): F[UUID]
}

object UUIDSupport {

  def apply[F[_]: UUIDSupport]: UUIDSupport[F] = implicitly

  implicit def uuidSupport[F[_]: Sync]: UUIDSupport[F] =
    new UUIDSupport[F] {
      def random: F[UUID]            = Sync[F].delay(UUID.randomUUID())
      def read(str: String): F[UUID] = Sync[F].delay(UUID.fromString(str))
    }
}