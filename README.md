THIS PROJECT IS ARCHIVED. IT IS NOW PART OF FS2 ITSELF, SEE https://github.com/functional-streams-for-scala/fs2/pull/1334

# fs2-gzip [![Build Status](https://travis-ci.org/slamdata/fs2-gzip.svg?branch=master)](https://travis-ci.org/slamdata/fs2-gzip) [![Bintray](https://img.shields.io/bintray/v/slamdata-inc/maven-public/fs2-gzip.svg)](https://bintray.com/slamdata-inc/maven-public/fs2-gzip) [![Discord](https://img.shields.io/discord/373302030460125185.svg?logo=discord)](https://discord.gg/QNjwCg6)

A pair of incremental streaming combinators for performing GZIP compression/decompression on top of fs2 streams. The implementation delegates to `java.util.zip.GZIPInputStream`/`GZIPOutputStream`. Async evaluation is achieved without the use of blocking or thread pools by raising a sentinel exception and catching it within the stream processing loop.

This is, to the best of my knowledge, the only *streaming* GZIP support available on the JVM (Spray has something close, but still uses blocking reads as far as I can tell).

## Usage

```sbt
resolvers += Resolver.bintrayRepo("slamdata-inc", "maven-public")
libraryDependencies += "com.slamdata" %% "fs2-gzip" % <version>
```

With this package (which transitively brings in `fs2` 1.0.0), you should be able to do things like the following:

```scala
import cats.effect.IO
import fs2._

val result: Array[Byte] = 
  Stream("Hello, World!".getBytes: _*)
    .through(gzip.compress[IO](1024))
    .through(gzip.decompress[IO](1024))
    .compile.to[Array]
    .unsafeRunSync()

new String(result)    // => "Hello, World!"
```

Yay! Identity functions are cool. I guess. You can short-circuit that process to convince yourself that something is likely happening:

```scala
import cats.effect.IO
import fs2._

Stream("Hello, World!".getBytes: _*)
  .through(gzip.compress[IO](1024))
  .compile.to[Array]
  .unsafeRunSync()

// => Array(31, -117, 8, 0, 0, 0, 0, 0, 0, 0, -14, 72, -51, -55, -55, -41, 81, 8, -49, 47, -54, 73, 81, 4, 0, 0, 0, -1, -1, 3, 0, -48, -61, 74, -20, 13, 0, 0, 0)
```

Oooookay, probably also not impressive, especially since this is in fact *larger* than the input. Let's try something a bit more elaborate involving fs2-io:

```scala
import cats.effect.IO
import cats.instances.int._
import fs2._

import scala.concurrent.ExecutionContext
import java.nio.file.Paths
import java.util.concurrent.Executors

val BlockingExecutionContext =
  ExecutionContext.fromExecutorService(Executors.newCachedThreadPool)
implicit val CS = IO.contextShift(ExecutionContext.global)

io.file.readAll[IO](Paths.get("README.md"), BlockingExecutionContext, 4096)
  .through(gzip.compress[IO](4096))
  .chunks
  .foldMap(_.size)
  .compile.last
  .unsafeRunSync()    // => Some(997)

io.file.readAll[IO](Paths.get("README.md"), BlockingExecutionContext, 4096)
  .chunks
  .foldMap(_.size)
  .compile.last
  .unsafeRunSync()    // => Some(2072)
```

Now we're getting somewhere! Compression of some sort is definitely happening. And if you look at the unit tests, you can convince yourself that this compression is in fact GZIP, since we have tests which shell out to `gzip`/`gunzip` to ensure output compatibility.
