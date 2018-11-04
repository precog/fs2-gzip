/*
 * Copyright 2014–2018 SlamData Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fs2.gzip

import cats.effect.Sync

import scala.collection.mutable

import scala.{math, Array, Boolean, Byte, Int}
import scala.util.control.NoStackTrace

import java.io.InputStream
import java.lang.{Error, SuppressWarnings, System}

/**
 * An in-memory buffered byte InputStream designed to fake continuation suspension by throwing
 * exceptions. This will work so long as any delegating code (such as other InputStreams) perform
 * reads *before* changing any internal state. Reads that are interleaved with state changes may
 * result in invalid continuations.
 *
 * TODO we need checkpointing because not all reads are done in one shot (e.g. read(); read(); read();)
 */
@SuppressWarnings(Array("org.wartremover.warts.Var"))
private[gzip] final class AsyncByteArrayInputStream[F[_]: Sync] private (val bound: Int) extends InputStream {
  private[this] val bytes = new mutable.ListBuffer[Array[Byte]]
  private[this] var headOffset = 0
  private[this] var _available = 0

  def push(chunk: Array[Byte]): F[Boolean] = {
    Sync[F] delay {
      if (available < bound) {
        val _ = bytes += chunk
        _available += chunk.length
        true
      } else {
        false
      }
    }
  }

  override def available() = _available

  def read(): Int = {
    val buf = new Array[Byte](1)
    val _ = read(buf)
    buf(0) & 0xff
  }

  @SuppressWarnings(Array("org.wartremover.warts.TraversableOps", "org.wartremover.warts.Throw"))
  override def read(target: Array[Byte], off: Int, len: Int): Int = {
    if (bytes.isEmpty) {
      throw AsyncByteArrayInputStream.AsyncError
    } else {
      val head = bytes.head
      val copied = math.min(len, head.length - headOffset)
      System.arraycopy(head, headOffset, target, off, copied)

      _available -= copied

      val _ = headOffset += copied

      if (headOffset >= head.length) {
        headOffset = 0
        val _ = bytes.remove(0)
      }

      copied
    }
  }
}

private[gzip] object AsyncByteArrayInputStream {

  def apply[F[_]: Sync](bound: Int): F[AsyncByteArrayInputStream[F]] =
    Sync[F].delay(new AsyncByteArrayInputStream[F](bound))

  case object AsyncError extends Error with NoStackTrace
}
