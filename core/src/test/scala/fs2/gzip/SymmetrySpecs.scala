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

import cats.effect.IO

import org.specs2.mutable._

import scala.{Byte, List}

object SymmetrySpecs extends Specification {

  "symmetric compress/decompress" should {
    "compress some simple bytes to a hard-coded result" in {
      val input = Stream[IO, Byte](1, 2, 3, 4, 5)
      val expected = List[Byte](31, -117, 8, 0, 0, 0, 0, 0, 0, 0, 98, 100, 98, 102, 97, 5, 0, 0, 0, -1, -1)
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

    "round-trip some simple bytes" in {
      val input = Stream[Pure, Byte](1, 2, 3, 4, 5)
      val output = input.through(compress[IO](8192)).through(decompress[IO](8192))
      output.compile.toList.unsafeRunSync() mustEqual input.toList
    }
  }
}
