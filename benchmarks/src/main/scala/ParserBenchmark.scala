// Copyright (c) 2016-2023 Association of Universities for Research in Astronomy, Inc. (AURA)
// Copyright (c) 2016-2023 Grackle Contributors
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package grackle.benchmarks

import grackle.Schema
import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit
import scala.io.Source

/**
 * To do comparative benchmarks between versions:
 *
 *     benchmarks/run-benchmark ParserBenchmark
 *
 * This will generate results in `benchmarks/results`.
 *
 * Or to run the benchmark from within sbt:
 *
 *     jmh:run -i 10 -wi 10 -f 2 -t 1 grackle.benchmarks.ParserBenchmark
 *
 * Which means "10 iterations", "10 warm-up iterations", "2 forks", "1 thread".
 * Please note that benchmarks should be usually executed at least in
 * 10 iterations (as a rule of thumb), but more is better.
 */
@State(Scope.Thread)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.SECONDS)
class ParserBenchmark {

  @Param(Array("100"))
  var size: Int = _

  val schema = Source.fromResource("github.graphql").mkString

  @Benchmark
  def parseSchema(blackhole: Blackhole) = {
    for (_ <- 0 to size) {
      val parsed = Schema(schema)
      blackhole.consume(parsed)
    }
  }

}

