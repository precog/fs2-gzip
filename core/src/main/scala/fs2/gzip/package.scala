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

package fs2

import cats.effect.Sync
import cats.syntax.all._

import scala.{Array, Byte, Int, None, Option, Some}

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.lang.{SuppressWarnings, System}
import java.util.zip.{GZIPInputStream, GZIPOutputStream}

package object gzip {

  // try to align initialBufferSize with your expected chunk size
  @SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
  def compress[F[_]: Sync](initialBufferSize: Int = 8192): Pipe[F, Byte, Byte] = { in =>
    for {
      bos <- Stream.eval(Sync[F].delay(new ByteArrayOutputStream(initialBufferSize)))

      gzos <- Stream.bracket(Sync[F].delay(new GZIPOutputStream(bos))) { gzos =>
        Sync[F].delay(gzos.close())
      }

      b <- in.chunks flatMap { chunk =>
        Stream evalUnChunk {
          for {
            _ <- Sync[F] delay {
              chunk match {
                case Chunk.Bytes(values, off, len) =>
                  gzos.write(values, off, len)

                case Chunk.ByteVectorChunk(bv) =>
                  bv.copyToStream(gzos)

                // TODO is there a better way of doing this?
                case chunk =>
                  val len = chunk.size
                  val buf = new Array[Byte](len)

                  chunk.copyToArray(buf, 0)
                  gzos.write(buf)
              }
            }

            _ <- Sync[F].delay(gzos.flush())    // eagerly flush on each chunk

            arr <- Sync[F] delay {
              val back = bos.toByteArray
              bos.reset()
              back
            }
          } yield Chunk.bytes(arr)
        }
      }
    } yield b
  }

  // try to align initialBufferSize with your expected chunk size
  // output chunks will be bounded by double this value
  @SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
  def decompress[F[_]: Sync](bufferSize: Int = 8192): Pipe[F, Byte, Byte] = { in =>
    val streams = for {
      buf <- Stream.eval(Sync[F].delay(new Array[Byte](bufferSize)))
      bis <- Stream.eval(Sync[F].delay(new ByteArrayInputStream(buf)))

      gzis <- Stream.bracket(Sync[F].delay(new GZIPInputStream(bis))) { gzis =>
        Sync[F].delay(gzis.close())
      }
    } yield (buf, bis, gzis)

    streams flatMap {
      case (buf, bis, gzis) =>
        val stepDecompress = Stream force {
          Sync[F] delay {
            val inner = new Array[Byte](bufferSize * 2)   // double the input buffer size since we're decompressing
            val len = gzis.read(inner)

            if (len > 0)
              Stream.chunk(Chunk.bytes(inner, 0, len)).covary[F]
            else
              Stream.empty[F]
          }
        }

        /*
         * Pull one chunk at a time and page it through buf. Anything which doesn't fit in buf
         * goes into leftover. Note that leftover may be larger than buf. Ensure that leftover
         * is fully emptied before we pull the next input chunk. Whenever we get something into
         * buf, step the decompression process by pulling on gzis and converting the result into
         * an output chunk if bytes were read. If we fail to make any progress (i.e. we remain
         * a the same offset in buf after stepping) and buf is full (i.e. leftover != None), then
         * presumably the GZIP decompression algorithm needs a larger block in order to make
         * progress and the stream will halt with an error.
         */
        @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
        def loop(in: Stream[F, Byte], leftover: Option[(Array[Byte], Int)]): Stream[F, Byte] = {
          Stream force {
            for {
              // if we have leftovers in the array, move them to the front and ensure that subsequent
              // copying only adds to what is there
              offset <- Sync[F] delay {
                val trailing = bis.available()
                if (trailing > 0) {
                  System.arraycopy(buf, buf.length - trailing, buf, 0, trailing)    // uh, does this work?? how about when trailing > buf.length / 2 ?
                }

                trailing
              }

              (offset2, leftover2) <- leftover match {
                case Some((stretch, soff)) =>
                  if (stretch.length - (soff + offset) < buf.length) {
                    Sync[F] delay {
                      System.arraycopy(stretch, soff, buf, offset, stretch.length - soff)
                      (soff + offset, None)   // we got the whole thing into the buffer
                    }
                  } else {
                    Sync[F] delay {
                      System.arraycopy(stretch, soff, buf, offset, buf.length - offset)
                      (buf.length, Some((stretch, soff + (buf.length - offset))))   // we only copied a subset
                    }
                  }

                case None =>
                  (offset, None).pure[F]
              }

              _ <- Sync[F].delay(bis.reset())   // we moved any leftover bytes to the front, so reset position to 0

              back = if (offset2 < buf.length) {    // leftover2 == None
                // if we have new space in buf, fill it!
                val mapped = in.pull.uncons map {
                  case Some((chunk, tail)) =>
                    Stream force {
                      val eff = Sync[F] delay {
                        if (chunk.size <= buf.length - offset2) {
                          chunk.copyToArray(buf, 0)
                          None
                        } else {
                          val stretch = new Array[Byte](chunk.size)
                          chunk.copyToArray(stretch, 0)

                          System.arraycopy(stretch, 0, buf, 0, buf.length - offset2)
                          Some((stretch, stretch.length - (buf.length - offset2)))
                        }
                      }

                      eff.map(leftover3 => stepDecompress ++ loop(tail, leftover3))
                    }

                  case None =>
                    // we finished with the input stream, so finish up leftover *and* buf and we're done
                    if (leftover2.isDefined) {
                      stepDecompress ++ loop(Stream.empty, leftover2)
                    } else {
                      lazy val stepAll: Stream[F, Byte] = Stream force {
                        Sync[F] delay {
                          if (bis.available() > 0)
                            stepDecompress ++ stepAll
                          else
                            Stream.empty[F]
                        }
                      }

                      stepAll
                    }
                }

                mapped.stream.flatten
              } else {
                // buf is full, don't pull another chunk until we can consume further on our leftovers
                if (leftover.map(_._2).flatMap(o => leftover2.map(o - _._2 > 0)).getOrElse(true))   // sanity check to make sure we're shrinking leftover; if we aren't, it probably means the buffer is too small for the gzip algorithm
                  stepDecompress ++ loop(in, leftover2)
                else
                  Stream.raiseError(NonProgressiveDecompressionException(bufferSize))   // bomb the stream if we failed to make any progress whatsoever (otherwise we would just loop forever here)
              }
            } yield back
          }
        }

        loop(in, None)
    }
  }
}
