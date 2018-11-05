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

import scala.{Array, Byte, Int, None, Some, Unit}
// import scala.{Predef, StringContext}, Predef._
import scala.util.{Left, Right}

import java.io.ByteArrayOutputStream
import java.lang.SuppressWarnings
import java.util.zip.{GZIPInputStream, GZIPOutputStream}

package object gzip {

  // try to align initialBufferSize with your expected chunk size
  def compress[F[_]: Sync](initialBufferSize: Int): Pipe[F, Byte, Byte] = { in =>
    for {
      bos <- Stream.eval(Sync[F].delay(new ByteArrayOutputStream(initialBufferSize)))
      gzos <- Stream.eval(Sync[F].delay(new GZIPOutputStream(bos, true)))

      slurpBytes = Sync[F] delay {
        val back = bos.toByteArray
        bos.reset()
        back
      }

      body = in.chunks flatMap { chunk =>
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

            arr <- slurpBytes
          } yield Chunk.bytes(arr)
        }
      }

      b <- body ++ Stream.eval_(Sync[F].delay(gzos.close())) ++ Stream.evalUnChunk(slurpBytes.map(Chunk.bytes(_)))
    } yield b
  }

  // try to align initialBufferSize with your expected chunk size
  // output chunks will be bounded by double this value
  def decompress[F[_]: Sync](bufferSize: Int): Pipe[F, Byte, Byte] = { in =>
    Stream.eval(AsyncByteArrayInputStream(bufferSize)) flatMap { abis =>
      def push(chunk: Chunk[Byte]): F[Unit] = {
        for {
          arr <- Sync[F] delay {
            val buf = new Array[Byte](chunk.size)
            chunk.copyToArray(buf)    // TODO we can be slightly better than this for Chunk.Bytes if we track incoming offsets in abis
            buf
          }

          pushed <- abis.push(arr)

          _ <- if (!pushed)
            Sync[F].raiseError(NonProgressiveDecompressionException(bufferSize))
          else
            ().pure[F]
        } yield ()
      }

      @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
      def pageBeginning(in: Stream[F, Byte]): Pull[F, (GZIPInputStream, Stream[F, Byte]), Unit] = {
        in.pull.uncons flatMap {
          case Some((chunk, tail)) =>
            val tryAcquire = abis.checkpoint >> Sync[F].delay(new GZIPInputStream(abis)).attempt   // GZIPInputStream has no resources, so we don't need to bracket
            val createOrLoop = Pull.eval(tryAcquire) flatMap {
              case Right(gzis) => Pull.output1((gzis, tail)) >> Pull.eval(abis.release) >> Pull.done
              case Left(AsyncByteArrayInputStream.AsyncError) => Pull.eval(abis.restore) >> pageBeginning(tail)
              case Left(t) => Pull.raiseError(t)
            }

            Pull.eval(push(chunk)) >> createOrLoop

          // we got all the way to the end of the input without moving forward
          case None =>
            Pull.raiseError(NonProgressiveDecompressionException(bufferSize))
        }
      }

      pageBeginning(in).stream flatMap {
        case (gzis, in) =>
          val stepDecompress = Stream force {
            Sync[F] delay {
              val inner = new Array[Byte](bufferSize * 2)   // double the input buffer size since we're decompressing

              val len = try {
                gzis.read(inner)
              } catch {
                case AsyncByteArrayInputStream.AsyncError => 0
              }

              if (len > 0)
                Stream.chunk(Chunk.bytes(inner, 0, len)).covary[F]
              else
                Stream.empty[F]
            }
          }

          // drains abis through gzis
          @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
          lazy val repeatedlyDecompress: Stream[F, Byte] = Stream force {
            Sync[F] delay {
              if (abis.available() > 0)
                stepDecompress ++ repeatedlyDecompress
              else
                Stream.empty[F]
            }
          }

          val mainline = in.chunks flatMap { chunk =>
            Stream.eval_(push(chunk)) ++ stepDecompress
          }

          mainline ++ repeatedlyDecompress
      }
    }
  }
}
