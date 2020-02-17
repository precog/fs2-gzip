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
package gzip

import cats.effect.{Blocker, IO}
import org.specs2.mutable._
import org.specs2.matcher.ThrownMessages

import scala.{Byte, List}
// import scala.{Predef, StringContext}, Predef._
import scala.concurrent.ExecutionContext
import scala.sys.process._

import java.io.File
import java.util.concurrent.Executors

object SymmetrySpecs extends Specification with ThrownMessages {

  val blocker = Blocker.liftExecutionContext(ExecutionContext.fromExecutorService(Executors.newCachedThreadPool))
  implicit val CS = IO.contextShift(ExecutionContext.global)

  val TextFilePath = "core/src/test/resources/winnie.txt"

  val TextFileLines =
    io.file.readAll[IO](new File(TextFilePath).toPath, blocker, 4096)
      .through(text.utf8Decode)
      .through(text.lines)
      .compile.toList
      .unsafeRunSync()

  "symmetric compress/decompress" should {
    // the hard-coded tests here are relatively fragile. at least the compress one is. treat with care
    "compress some simple bytes to a hard-coded result" in {
      val input = Stream[IO, Byte](1, 2, 3, 4, 5)
      val expected = List[Byte](31, -117, 8, 0, 0, 0, 0, 0, 0, 0, 98, 100, 98, 102, 97, 5, 0, 0, 0, -1, -1, 3, 0, -12, -103, 11, 71, 5, 0, 0, 0)
      input.through(compress[IO](8192)).compile.toList.unsafeRunSync() mustEqual expected
    }

    "decompress hard-coded bytes to simple result" in {
      val input = Stream[IO, Byte](31, -117, 8, 0, 0, 0, 0, 0, 0, 0, 98, 100, 98, 102, 97, 5, 0, 0, 0, -1, -1)
      val expected = List[Byte](1, 2, 3, 4, 5)
      input.through(decompress[IO](8192)).compile.toList.unsafeRunSync() mustEqual expected
    }

    "decompress hard-coded gerrymandered bytes to simple result" in {
      val input = Stream[IO, Byte](31, -117, 8, 0, 0, 0, 0, 0, 0, 0, 98, 100, 98, 102, 97, 5, 0, 0, 0, -1, -1).unchunk
      val expected = List[Byte](1, 2, 3, 4, 5)
      input.through(decompress[IO](8192)).compile.toList.unsafeRunSync() mustEqual expected
    }

    "compress with gzip and then decompress" in {
      if ((stringToProcess("which gzip") ! ProcessLogger(_ => (), _ => ())) == 0) {
        val scratchgz = File.createTempFile("SymmetrySpecs-gzip-then-decompress", "winnie.txt.gz")
        ("gzip" #< (new File(TextFilePath)) #> scratchgz).! mustEqual 0

        // nice equal block sizes; we can do better ^_^
        val lines =
          io.file.readAll[IO](scratchgz.toPath, blocker, 512)
            .through(decompress[IO](4096))
            .through(text.utf8Decode)
            .through(text.lines)
            .compile.toList
            .unsafeRunSync()

        lines mustEqual TextFileLines
      } else {
        skip("gzip executable not found on this system")
      }
    }

    "compress with gzip and then decompress with prime chunk sizes (decompression smaller)" in {
      if ((stringToProcess("which gzip") ! ProcessLogger(_ => (), _ => ())) == 0) {
        val scratchgz = File.createTempFile("SymmetrySpecs-gzip-then-decompress", "winnie.txt.gz")
        ("gzip" #< (new File(TextFilePath)) #> scratchgz).! mustEqual 0

        val lines =
          io.file.readAll[IO](scratchgz.toPath, blocker, 509)
            .through(decompress[IO](311))
            .through(text.utf8Decode)
            .through(text.lines)
            .compile.toList
            .unsafeRunSync()

        lines mustEqual TextFileLines
      } else {
        skip("gzip executable not found on this system")
      }
    }

    "compress with gzip and then decompress with prime chunk sizes (decompression MUCH smaller)" in {
      if ((stringToProcess("which gzip") ! ProcessLogger(_ => (), _ => ())) == 0) {
        val scratchgz = File.createTempFile("SymmetrySpecs-gzip-then-decompress", "winnie.txt.gz")
        ("gzip" #< (new File(TextFilePath)) #> scratchgz).! mustEqual 0

        val lines =
          io.file.readAll[IO](scratchgz.toPath, blocker, 509)
            .through(decompress[IO](83))
            .through(text.utf8Decode)
            .through(text.lines)
            .compile.toList
            .unsafeRunSync()

        lines mustEqual TextFileLines
      } else {
        skip("gzip executable not found on this system")
      }
    }

    "compress with gzip and then decompress with prime chunk sizes (decompression larger)" in {
      if ((stringToProcess("which gzip") ! ProcessLogger(_ => (), _ => ())) == 0) {
        val scratchgz = File.createTempFile("SymmetrySpecs-gzip-then-decompress", "winnie.txt.gz")
        ("gzip" #< (new File(TextFilePath)) #> scratchgz).! mustEqual 0

        val lines =
          io.file.readAll[IO](scratchgz.toPath, blocker, 509)
            .through(decompress[IO](1031))
            .through(text.utf8Decode)
            .through(text.lines)
            .compile.toList
            .unsafeRunSync()

        lines mustEqual TextFileLines
      } else {
        skip("gzip executable not found on this system")
      }
    }

    "compress and then decompress with gunzip" in {
      if ((stringToProcess("which gunzip") ! ProcessLogger(_ => (), _ => ())) == 0) {
        val scratchgz = File.createTempFile("SymmetrySpecs-gzip-then-decompress", "winnie.txt.gz")
        val scratchtxt = File.createTempFile("SymmetrySpecs-gzip-then-decompress", "winnie.txt")

        io.file.readAll[IO](new File(TextFilePath).toPath, blocker, 509)
          .through(compress[IO](509))
          .through(io.file.writeAll[IO](scratchgz.toPath, blocker))
          .compile.drain.unsafeRunSync()

        ("gunzip" #< scratchgz #> scratchtxt).! mustEqual 0

        val lines =
          io.file.readAll[IO](scratchtxt.toPath, blocker, 4096)
            .through(text.utf8Decode)
            .through(text.lines)
            .compile.toList
            .unsafeRunSync()

        lines mustEqual TextFileLines
      } else {
        skip("gunzip executable not found on this system")
      }
    }

    "round-trip some simple bytes" in {
      val input = Stream[Pure, Byte](1, 2, 3, 4, 5)
      val output = input.through(compress[IO](8192)).through(decompress[IO](8192))
      output.compile.toList.unsafeRunSync() mustEqual input.toList
    }

    "round-trip text file with small buffers" in {
      val lines =
        io.file.readAll[IO](new File(TextFilePath).toPath, blocker, 1024)
          .through(compress[IO](1024))
          .through(decompress[IO](1024))
          .through(text.utf8Decode)
          .through(text.lines)
          .compile.toList
          .unsafeRunSync()

      lines mustEqual TextFileLines
    }

    "round-trip text file with adaptive buffers" in {
      val lines =
        io.file.readAll[IO](new File(TextFilePath).toPath, blocker, 512)
          .through(compressAdaptive[IO])
          .through(decompressAdaptive[IO])
          .through(text.utf8Decode)
          .through(text.lines)
          .compile.toList
          .unsafeRunSync()

      lines mustEqual TextFileLines
    }
  }
}
