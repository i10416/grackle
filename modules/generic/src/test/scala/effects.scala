// Copyright (c) 2016-2020 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package edu.gemini.grackle
package generic

import cats.effect.{IO, Sync}
import cats.effect.unsafe.implicits.global
import cats.implicits._
import cats.tests.CatsSuite
import fs2.concurrent.SignallingRef
import io.circe.Json

import edu.gemini.grackle.syntax._

class GenericEffectMapping[F[_]: Sync](ref: SignallingRef[F, Int]) extends GenericMapping[F] {
  import semiauto._

  val schema =
    schema"""
      type Query {
        foo: Struct!
      }
      type Struct {
        n: Int!
        s: String!
      }
    """

  val QueryType  = schema.ref("Query")
  val StructType = schema.ref("Struct")

  case class Wrapper(value: Int)
  case class Struct(n: Wrapper, s: String)
  object Struct {
    implicit val wrappedCb: CursorBuilder[Wrapper]    = CursorBuilder[Int].contramap(_.value)
    implicit val cursorBuilder: CursorBuilder[Struct] = deriveObjectCursorBuilder[Struct](StructType)
  }

  val typeMappings = List(
    ObjectMapping(
      tpe = QueryType,
      fieldMappings =
        List(
          // Compute a ValueCursor
          RootEffect.computeCursor("foo")((_, p, e) =>
            ref.update(_+1).as(
              genericCursor(p, e, Struct(Wrapper(42), "hi"))
            )
          )
        )
    )
  )
}

final class GenericEffectSpec extends CatsSuite {
  test("generic effect") {
    val query = """
      query {
        foo {
          s,
          n
        }
      }
    """

    val expected = json"""
      {
        "data" : {
          "foo" : {
            "s" : "hi",
            "n" : 42
          }
        }
      }
    """

    val prg: IO[(Json, Int)] =
      for {
        ref <- SignallingRef[IO, Int](0)
        map = new GenericEffectMapping(ref)
        res <- map.compileAndRun(query)
        eff <- ref.get
      } yield (res, eff)

    val (res, eff) = prg.unsafeRunSync()

    assert(res == expected)
    assert(eff == 1)
  }

  test("generic effect, aliased") {
    val query = """
      query {
        quux:foo {
          s,
          n
        }
      }
    """

    val expected = json"""
      {
        "data" : {
          "quux" : {
            "s" : "hi",
            "n" : 42
          }
        }
      }
    """

    val prg: IO[(Json, Int)] =
      for {
        ref <- SignallingRef[IO, Int](0)
        map = new GenericEffectMapping(ref)
        res <- map.compileAndRun(query)
        eff <- ref.get
      } yield (res, eff)

    val (res, eff) = prg.unsafeRunSync()

    assert(res == expected)
    assert(eff == 1)
  }
}
