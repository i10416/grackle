// Copyright (c) 2016-2020 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package edu.gemini.grackle.sql.test


import java.time.{Duration, LocalDate, LocalTime, ZonedDateTime}
import java.util.UUID

import scala.util.Try

import cats.{Eq, Order}
import cats.implicits._
import io.circe.Encoder

import edu.gemini.grackle._
import syntax._
import Query._
import Path._
import Predicate._
import Value._
import QueryCompiler._

trait SqlMovieMapping[F[_]] extends SqlTestMapping[F] { self =>
  
  def genre: Codec
  def feature: Codec

  object movies extends TableDef("movies") {
    val id = col("id", uuid)
    val title = col("title", text)
    val genre = col("genre", self.genre)
    val releaseDate = col("releasedate", localDate)
    val showTime = col("showtime", localTime)
    val nextShowing = col("nextshowing", zonedDateTime)
    val duration = col("duration", self.duration)
    val categories = col("categories", list(varchar))
    val features = col("features", list(feature))
  }

  val schema =
    schema"""
        type Query {
          movieById(id: UUID!): Movie
          moviesByGenre(genre: Genre!): [Movie!]!
          moviesByGenres(genres: [Genre!]): [Movie!]!
          moviesReleasedBetween(from: Date!, to: Date!): [Movie!]!
          moviesLongerThan(duration: Interval!): [Movie!]!
          moviesShownLaterThan(time: Time!): [Movie!]!
          moviesShownBetween(from: DateTime!, to: DateTime!): [Movie!]!
          longMovies: [Movie!]!
        }
        scalar UUID
        scalar Time
        scalar Date
        scalar DateTime
        scalar Interval
        enum Genre {
          DRAMA
          ACTION
          COMEDY
        }
        enum Feature {
          HD
          HLS
        }
        type Movie {
          id: UUID!
          title: String!
          genre: Genre!
          releaseDate: Date!
          showTime: Time!
          nextShowing: DateTime!
          nextEnding: DateTime!
          duration: Interval!
          categories: [String!]!
          features: [Feature!]!
        }
      """

  val QueryType = schema.ref("Query")
  val MovieType = schema.ref("Movie")
  val UUIDType = schema.ref("UUID")
  val TimeType = schema.ref("Time")
  val DateType = schema.ref("Date")
  val DateTimeType = schema.ref("DateTime")
  val IntervalType = schema.ref("Interval")
  val GenreType = schema.ref("Genre")
  val FeatureType = schema.ref("Feature")
  val RatingType = schema.ref("Rating")

  val typeMappings =
    List(
      ObjectMapping(
        tpe = QueryType,
        fieldMappings =
          List(
            SqlRoot("movieById"),
            SqlRoot("moviesByGenre"),
            SqlRoot("moviesByGenres"),
            SqlRoot("moviesReleasedBetween"),
            SqlRoot("moviesLongerThan"),
            SqlRoot("moviesShownLaterThan"),
            SqlRoot("moviesShownBetween"),
            SqlRoot("longMovies")
          )
      ),
      ObjectMapping(
        tpe = MovieType,
        fieldMappings =
          List(
            SqlField("id", movies.id, key = true),
            SqlField("title", movies.title),
            SqlField("genre", movies.genre),
            SqlField("releaseDate", movies.releaseDate),
            SqlField("showTime", movies.showTime),
            SqlField("nextShowing", movies.nextShowing),
            CursorField("nextEnding", nextEnding, List("nextShowing", "duration")),
            SqlField("duration", movies.duration),
            SqlField("categories", movies.categories),
            SqlField("features", movies.features),
            CursorField("isLong", isLong, List("duration"), hidden = true)
          )
      ),
      LeafMapping[UUID](UUIDType),
      LeafMapping[LocalTime](TimeType),
      LeafMapping[LocalDate](DateType),
      LeafMapping[ZonedDateTime](DateTimeType),
      LeafMapping[Duration](IntervalType),
      LeafMapping[Genre](GenreType),
      LeafMapping[Feature](FeatureType),
      LeafMapping[List[Feature]](ListType(FeatureType))
    )

  def nextEnding(c: Cursor): Result[ZonedDateTime] =
    for {
      nextShowing <- c.fieldAs[ZonedDateTime]("nextShowing")
      duration    <- c.fieldAs[Duration]("duration")
    } yield nextShowing.plus(duration)

  def isLong(c: Cursor): Result[Boolean] =
    for {
      duration    <- c.fieldAs[Duration]("duration")
    } yield duration.toHours >= 3

  object UUIDValue {
    def unapply(s: StringValue): Option[UUID] =
      Try(UUID.fromString(s.value)).toOption
  }

  object DateValue {
    def unapply(s: StringValue): Option[LocalDate] =
      Try(LocalDate.parse(s.value)).toOption
  }

  object TimeValue {
    def unapply(s: StringValue): Option[LocalTime] =
      Try(LocalTime.parse(s.value)).toOption
  }

  object DateTimeValue {
    def unapply(s: StringValue): Option[ZonedDateTime] =
      Try(ZonedDateTime.parse(s.value)).toOption
  }

  object IntervalValue {
    def unapply(s: StringValue): Option[Duration] =
      Try(Duration.parse(s.value)).toOption
  }

  object GenreValue {
    def unapply(e: TypedEnumValue): Option[Genre] =
      Genre.fromString(e.value.name)
  }

  object GenreListValue {
    def unapply(gs: ListValue): Option[List[Genre]] =
      gs.elems.traverse {
        case GenreValue(g) => Some(g)
        case _ => None
      }
  }

  override val selectElaborator = new SelectElaborator(Map(
    QueryType -> {
      case Select("movieById", List(Binding("id", UUIDValue(id))), child) =>
        Select("movieById", Nil, Unique(Filter(Eql(UniquePath(List("id")), Const(id)), child))).rightIor
      case Select("moviesByGenre", List(Binding("genre", GenreValue(genre))), child) =>
        Select("moviesByGenre", Nil, Filter(Eql(UniquePath(List("genre")), Const(genre)), child)).rightIor
      case Select("moviesByGenres", List(Binding("genres", GenreListValue(genres))), child) =>
        Select("moviesByGenres", Nil, Filter(In(UniquePath(List("genre")), genres), child)).rightIor
      case Select("moviesReleasedBetween", List(Binding("from", DateValue(from)), Binding("to", DateValue(to))), child) =>
        Select("moviesReleasedBetween", Nil,
          Filter(
            And(
              Not(Lt(UniquePath(List("releaseDate")), Const(from))),
              Lt(UniquePath(List("releaseDate")), Const(to))
            ),
            child
          )
        ).rightIor
      case Select("moviesLongerThan", List(Binding("duration", IntervalValue(duration))), child) =>
        Select("moviesLongerThan", Nil,
          Filter(
            Not(Lt(UniquePath(List("duration")), Const(duration))),
            child
          )
        ).rightIor
      case Select("moviesShownLaterThan", List(Binding("time", TimeValue(time))), child) =>
        Select("moviesShownLaterThan", Nil,
          Filter(
            Not(Lt(UniquePath(List("showTime")), Const(time))),
            child
          )
        ).rightIor
      case Select("moviesShownBetween", List(Binding("from", DateTimeValue(from)), Binding("to", DateTimeValue(to))), child) =>
        Select("moviesShownBetween", Nil,
          Filter(
            And(
              Not(Lt(UniquePath(List("nextShowing")), Const(from))),
              Lt(UniquePath(List("nextShowing")), Const(to))
            ),
            child
          )
        ).rightIor
      case Select("longMovies", Nil, child) =>
        Select("longMovies", Nil, Filter(Eql(UniquePath(List("isLong")), Const(true)), child)).rightIor
    }
  ))

  sealed trait Genre extends Product with Serializable
  object Genre {
    case object Drama extends Genre
    case object Action extends Genre
    case object Comedy extends Genre

    implicit val genreEq: Eq[Genre] = Eq.fromUniversalEquals[Genre]

    def fromString(s: String): Option[Genre] =
      s.trim.toUpperCase match {
        case "DRAMA"  => Some(Drama)
        case "ACTION" => Some(Action)
        case "COMEDY" => Some(Comedy)
        case _ => None
      }

    implicit val genreEncoder: io.circe.Encoder[Genre] =
      Encoder[String].contramap(_ match {
        case Drama => "DRAMA"
        case Action => "ACTION"
        case Comedy => "COMEDY"
      })

    def fromInt(i: Int): Genre =
      (i: @unchecked) match {
        case 1 => Drama
        case 2 => Action
        case 3 => Comedy
      }

    def toInt(f: Genre): Int =
      f match {
        case Drama  => 1
        case Action => 2
        case Comedy => 3
      }
  }

  sealed trait Feature
  object Feature {
    case object HD extends Feature
    case object HLS extends Feature

    def fromString(s: String): Feature = (s.trim.toUpperCase: @unchecked) match {
      case "HD" => HD
      case "HLS" => HLS
    }

    implicit def featureEncoder: io.circe.Encoder[Feature] =
      Encoder[String].contramap(_.toString)
  }

  implicit val localDateOrder: Order[LocalDate] =
    Order.from(_.compareTo(_))

  implicit val localTimeOrder: Order[LocalTime] =
    Order.fromComparable[LocalTime]

  implicit val zonedDateTimeOrder: Order[ZonedDateTime] =
    Order.from(_.compareTo(_))

  implicit val durationOrder: Order[Duration] =
    Order.fromComparable[Duration]
}