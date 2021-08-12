// Copyright (c) 2016-2020 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package edu.gemini.grackle

import cats.parse.{Parser, Parser0}
import cats.parse.Parser._
import cats.parse.Numbers._
import cats.parse.Rfc5234.{cr, crlf, digit, hexdig, lf}
import cats.implicits._
import CommentedText._
import Literals._

object GraphQLParser {

  def keyword(s: String) = token(string(s))

  lazy val Document: Parser0[Ast.Document] =
    (whitespace | comment).rep0 *> Definition.rep0 <* Parser.end

  lazy val Definition: Parser[Ast.Definition] =
    ExecutableDefinition | TypeSystemDefinition // | TypeSystemExtension

  lazy val TypeSystemDefinition: Parser[Ast.TypeSystemDefinition] = {
    val SchemaDefinition: Parser[Ast.SchemaDefinition] =
      ((keyword("schema") *> Directives.?) ~ braces(RootOperationTypeDefinition.rep0)).map {
        case (dirs, rootdefs) => Ast.SchemaDefinition(rootdefs, dirs.getOrElse(Nil))
      }

    def typeDefinition(desc: Option[Ast.Value.StringValue]): Parser[Ast.TypeDefinition] = {

      def scalarTypeDefinition(desc: Option[Ast.Value.StringValue]): Parser[Ast.ScalarTypeDefinition] =
        ((keyword("scalar") *> Name) ~ Directives.?).map {
          case (name, dirs) => Ast.ScalarTypeDefinition(name, desc.map(_.value), dirs.getOrElse(Nil))
        }

      def objectTypeDefinition(desc: Option[Ast.Value.StringValue]): Parser[Ast.ObjectTypeDefinition] =
        ((keyword("type") *> Name) ~ ImplementsInterfaces.? ~ Directives.? ~ FieldsDefinition).map {
          case (((name, ifs), dirs), fields) => Ast.ObjectTypeDefinition(name, desc.map(_.value), fields, ifs.getOrElse(Nil), dirs.getOrElse(Nil))
        }

      def interfaceTypeDefinition(desc: Option[Ast.Value.StringValue]): Parser[Ast.InterfaceTypeDefinition] =
        ((keyword("interface") *> Name) ~ ImplementsInterfaces.? ~ Directives.? ~ FieldsDefinition).map {
          case (((name, ifs), dirs), fields) => Ast.InterfaceTypeDefinition(name, desc.map(_.value), fields, ifs.getOrElse(Nil), dirs.getOrElse(Nil))
        }

      def unionTypeDefinition(desc: Option[Ast.Value.StringValue]): Parser[Ast.UnionTypeDefinition] =
        ((keyword("union") *> Name) ~ Directives.? ~ UnionMemberTypes).map {
          case ((name, dirs), members) => Ast.UnionTypeDefinition(name, desc.map(_.value), dirs.getOrElse(Nil), members)
        }

      def enumTypeDefinition(desc: Option[Ast.Value.StringValue]): Parser[Ast.EnumTypeDefinition] =
        ((keyword("enum") *> Name) ~ Directives.? ~ EnumValuesDefinition).map {
          case ((name, dirs), values) => Ast.EnumTypeDefinition(name, desc.map(_.value), dirs.getOrElse(Nil), values)
        }

      def inputObjectTypeDefinition(desc: Option[Ast.Value.StringValue]): Parser[Ast.InputObjectTypeDefinition] =
        ((keyword("input") *> Name) ~ Directives.? ~ InputFieldsDefinition).map {
          case ((name, dirs), fields) => Ast.InputObjectTypeDefinition(name, desc.map(_.value), fields, dirs.getOrElse(Nil))
        }

      scalarTypeDefinition(desc)|
        objectTypeDefinition(desc) |
        interfaceTypeDefinition(desc) |
        unionTypeDefinition(desc) |
        enumTypeDefinition(desc) |
        inputObjectTypeDefinition(desc)
    }

    def directiveDefinition(desc: Option[Ast.Value.StringValue]): Parser[Ast.DirectiveDefinition] =
      ((keyword("directive") *> keyword("@") *> Name) ~
         ArgumentsDefinition ~ (keyword("repeatable").? <* keyword("on")) ~ DirectiveLocations).map {
        case (((name, args), rpt), locs) => Ast.DirectiveDefinition(name, desc.map(_.value), args, rpt.isDefined, locs)
      }

    SchemaDefinition |
      Description.?.with1.flatMap { desc =>
        typeDefinition(desc) | directiveDefinition(desc)
      }
  }

  lazy val RootOperationTypeDefinition: Parser[Ast.RootOperationTypeDefinition] =
    (OperationType, keyword(":"), NamedType).mapN {
      case (optpe, _, tpe) => Ast.RootOperationTypeDefinition(optpe, tpe)
    }


  lazy val Description = StringValue

  lazy val ImplementsInterfaces =
    (keyword("implements") ~ keyword("&").?) *> NamedType.repSep0(keyword("&"))

  lazy val FieldsDefinition: Parser[List[Ast.FieldDefinition]] =
    braces(FieldDefinition.rep0)

  lazy val FieldDefinition: Parser[Ast.FieldDefinition] =
    (Description.?.with1 ~ Name ~ ArgumentsDefinition.? ~ keyword(":") ~ Type ~ Directives.?).map {
      case (((((desc, name), args), _), tpe), dirs) => Ast.FieldDefinition(name, desc.map(_.value), args.getOrElse(Nil), tpe, dirs.getOrElse(Nil))
    }

  lazy val ArgumentsDefinition: Parser[List[Ast.InputValueDefinition]] =
    parens(InputValueDefinition.rep0)

  lazy val InputFieldsDefinition: Parser[List[Ast.InputValueDefinition]] =
    braces(InputValueDefinition.rep0)

  lazy val InputValueDefinition: Parser[Ast.InputValueDefinition] =
    (Description.?.with1 ~ (Name <* keyword(":")) ~ Type ~ DefaultValue.? ~ Directives.?).map {
      case ((((desc, name), tpe), dv), dirs) => Ast.InputValueDefinition(name, desc.map(_.value), tpe, dv, dirs.getOrElse(Nil))
    }

  lazy val UnionMemberTypes: Parser[List[Ast.Type.Named]] =
    (keyword("=") ~ keyword("|").?) *> NamedType.repSep0(keyword("|"))

  lazy val EnumValuesDefinition: Parser[List[Ast.EnumValueDefinition]] =
    braces(EnumValueDefinition.rep0)

  lazy val EnumValueDefinition: Parser[Ast.EnumValueDefinition] =
    (Description.?.with1 ~ Name ~ Directives.?).map {
      case ((desc, name), dirs) => Ast.EnumValueDefinition(name, desc.map(_.value), dirs.getOrElse(Nil))
    }

  lazy val DirectiveLocations: Parser0[List[Ast.DirectiveLocation]] =
    keyword("|").? *> DirectiveLocation.repSep0(keyword("|"))

  lazy val DirectiveLocation: Parser[Ast.DirectiveLocation] =
    keyword("QUERY")       .as(Ast.DirectiveLocation.QUERY) |
    keyword("MUTATION")    .as(Ast.DirectiveLocation.MUTATION) |
    keyword("SUBSCRIPTION").as(Ast.DirectiveLocation.SUBSCRIPTION) |
    keyword("FIELD").as(Ast.DirectiveLocation.FIELD) |
    keyword("FRAGMENT_DEFINITION").as(Ast.DirectiveLocation.FRAGMENT_DEFINITION) |
    keyword("FRAGMENT_SPREAD").as(Ast.DirectiveLocation.FRAGMENT_SPREAD) |
    keyword("INLINE_FRAGMENT").as(Ast.DirectiveLocation.INLINE_FRAGMENT) |
    keyword("VARIABLE_DEFINITION").as(Ast.DirectiveLocation.VARIABLE_DEFINITION) |
    keyword("SCHEMA").as(Ast.DirectiveLocation.SCHEMA) |
    keyword("SCALAR").as(Ast.DirectiveLocation.SCALAR) |
    keyword("OBJECT").as(Ast.DirectiveLocation.OBJECT) |
    keyword("FIELD_DEFINITION").as(Ast.DirectiveLocation.FIELD_DEFINITION) |
    keyword("ARGUMENT_DEFINITION").as(Ast.DirectiveLocation.ARGUMENT_DEFINITION) |
    keyword("INTERFACE").as(Ast.DirectiveLocation.INTERFACE) |
    keyword("UNION").as(Ast.DirectiveLocation.UNION) |
    keyword("ENUM").as(Ast.DirectiveLocation.ENUM) |
    keyword("ENUM_VALUE").as(Ast.DirectiveLocation.ENUM_VALUE) |
    keyword("INPUT_OBJECT").as(Ast.DirectiveLocation.INPUT_OBJECT) |
    keyword("INPUT_FIELD_DEFINITION").as(Ast.DirectiveLocation.INPUT_FIELD_DEFINITION)

  lazy val ExecutableDefinition: Parser[Ast.ExecutableDefinition] =
    OperationDefinition | FragmentDefinition

  lazy val OperationDefinition: Parser[Ast.OperationDefinition] =
    QueryShorthand | Operation

  lazy val QueryShorthand: Parser[Ast.OperationDefinition.QueryShorthand] =
    SelectionSet.map(Ast.OperationDefinition.QueryShorthand.apply)

  lazy val Operation: Parser[Ast.OperationDefinition.Operation] =
    (OperationType ~ Name.? ~ VariableDefinitions.? ~ Directives ~ SelectionSet).map {
      case ((((op, name), vars), dirs), sels) => Ast.OperationDefinition.Operation(op, name, vars.orEmpty, dirs, sels)
    }

  lazy val OperationType: Parser[Ast.OperationType] =
    keyword("query")       .as(Ast.OperationType.Query) |
    keyword("mutation")    .as(Ast.OperationType.Mutation) |
    keyword("subscription").as(Ast.OperationType.Subscription)

  lazy val SelectionSet: Parser[List[Ast.Selection]] = recursive[List[Ast.Selection]] { rec =>

    val Alias: Parser[Ast.Name] =
      Name <* keyword(":")

    val Field: Parser[Ast.Selection.Field] =
      (Alias.backtrack.?.with1 ~ Name ~ Arguments.? ~ Directives ~ rec.?).map {
        case ((((alias, name), args), dirs), sel) => Ast.Selection.Field(alias, name, args.orEmpty, dirs, sel.orEmpty)
      }

    val FragmentSpread: Parser[Ast.Selection.FragmentSpread] =
      (FragmentName ~ Directives).map{ case (name, dirs) => Ast.Selection.FragmentSpread.apply(name, dirs)}

    val InlineFragment: Parser[Ast.Selection.InlineFragment] =
      ((TypeCondition.? ~ Directives).with1 ~ rec).map {
        case ((cond, dirs), sel) => Ast.Selection.InlineFragment(cond, dirs, sel)
      }

    val Selection: Parser[Ast.Selection] =
      Field |
      (keyword("...") *> (InlineFragment | FragmentSpread))

    braces(Selection.rep0)
  }

  lazy val Arguments: Parser[List[(Ast.Name, Ast.Value)]] =
    parens(Argument.rep0)

  lazy val Argument: Parser[(Ast.Name, Ast.Value)] =
    (Name <* keyword(":")) ~ Value

  lazy val FragmentName: Parser[Ast.Name] =
    Name.filter(_ != Ast.Name("on"))

  lazy val FragmentDefinition: Parser[Ast.FragmentDefinition] =
    ((keyword("fragment") *> FragmentName) ~ TypeCondition ~ Directives ~ SelectionSet).map {
      case (((name, cond), dirs), sel) => Ast.FragmentDefinition(name, cond, dirs, sel)
    }


  lazy val TypeCondition: Parser[Ast.Type.Named] =
    keyword("on") *> NamedType

  lazy val Value: Parser[Ast.Value] = recursive[Ast.Value] { rec =>

    val NullValue: Parser[Ast.Value.NullValue.type] =
      keyword("null").as(Ast.Value.NullValue)

    lazy val EnumValue: Parser[Ast.Value.EnumValue] =
      Name.filter {
        case Ast.Name("true")  => false
        case Ast.Name("false") => false
        case Ast.Name("null")  => false
        case _                 => true
      } .map(Ast.Value.EnumValue.apply)

    val ListValue: Parser[Ast.Value.ListValue] =
      token(squareBrackets(rec.rep0).map(Ast.Value.ListValue.apply))

    val IntValue: Parser[Ast.Value.IntValue] =
      token(intLiteral).map(a => Ast.Value.IntValue(a.toInt))

    val FloatValue: Parser[Ast.Value.FloatValue] =
      token(floatLiteral).map(a => Ast.Value.FloatValue(a.toDouble))

    val BooleanValue: Parser[Ast.Value.BooleanValue] =
      token(booleanLiteral).map(Ast.Value.BooleanValue.apply)

    val ObjectField: Parser[(Ast.Name, Ast.Value)] =
      (Name <* keyword(":")) ~ rec

    val ObjectValue: Parser[Ast.Value.ObjectValue] =
      braces(ObjectField.rep0).map(Ast.Value.ObjectValue.apply)

    Variable |
      IntValue.backtrack |
      FloatValue |
      StringValue |
      BooleanValue |
      NullValue |
      EnumValue |
      ListValue |
      ObjectValue
  }

  lazy val StringValue: Parser[Ast.Value.StringValue] =
    token(stringLiteral).map(Ast.Value.StringValue.apply)

  lazy val VariableDefinitions: Parser[List[Ast.VariableDefinition]] =
    parens(VariableDefinition.rep0)

  lazy val VariableDefinition: Parser[Ast.VariableDefinition] =
    ((Variable <* keyword(":")) ~ Type ~ DefaultValue.?).map {
      case ((v, tpe), dv) => Ast.VariableDefinition(v.name, tpe, dv)
    }

  lazy val Variable: Parser[Ast.Value.Variable] =
    keyword("$") *> Name.map(Ast.Value.Variable.apply)

  lazy val DefaultValue: Parser[Ast.Value] =
    keyword("=") *> Value

  lazy val Type: Parser[Ast.Type] = recursive[Ast.Type] { rec =>

    lazy val ListType: Parser[Ast.Type.List] =
      squareBrackets(rec).map(Ast.Type.List.apply)

    val namedMaybeNull: Parser[Ast.Type] = (NamedType ~ keyword("!").?).map {
      case (t, None) => t
      case (t, _) => Ast.Type.NonNull(Left(t))
    }

    val listMaybeNull: Parser[Ast.Type] = (ListType ~ keyword("!").?).map {
      case (t, None) => t
      case (t, _) => Ast.Type.NonNull(Right(t))
    }

    namedMaybeNull | listMaybeNull
  }

  lazy val NamedType: Parser[Ast.Type.Named] =
    Name.map(Ast.Type.Named.apply)

  lazy val Directives: Parser0[List[Ast.Directive]] =
    Directive.rep0

  lazy val Directive: Parser[Ast.Directive] =
    keyword("@") *> (Name ~ Arguments.?).map { case (n, ods) => Ast.Directive(n, ods.orEmpty)}

  lazy val Name: Parser[Ast.Name] = {
    val initial    = ('A' to 'Z') ++ ('a' to 'z') ++ Seq('_')
    val subsequent = initial ++ ('0' to '9')

    token(charIn(initial) ~ charIn(subsequent).rep0).map {
      case (h, t) => Ast.Name((h :: t).mkString)
    }
  }
}

object CommentedText {

  lazy val whitespace: Parser[Char] = charWhere(_.isWhitespace)

  def skipWhitespace: Parser0[Unit] =
    charsWhile0(c => c.isWhitespace || c == ',').void.withContext("whitespace")

  /** Parser that consumes a comment */
  def comment: Parser[Unit] =
    (char('#') *> (charWhere(c => c != '\n' && c != '\r')).rep0 <* charIn('\n', '\r') <* skipWhitespace).void.withContext("comment")

  /** Turns a parser into one that skips trailing whitespace and comments */
  def token[A](p: Parser[A]): Parser[A] =
    p <* skipWhitespace <* comment.rep0

  def token0[A](p: Parser0[A]): Parser0[A] =
    p <* skipWhitespace <* comment.rep0

  /**
   * Consumes `left` and `right`, including the trailing and preceding whitespace,
   * respectively, and returns the value of `p`.
   */
  private def _bracket[A,B,C](left: Parser[B], p: Parser0[A], right: Parser[C]): Parser[A] =
    token(left) *> token0(p) <* token(right)

  /** Turns a parser into one that consumes surrounding parentheses `()` */
  def parens[A](p: Parser0[A]): Parser[A] =
    _bracket(char('('), p, char(')')).withContext(s"parens(${p.toString})")

  /** Turns a parser into one that consumes surrounding curly braces `{}` */
  def braces[A](p: Parser0[A]): Parser[A] =
    _bracket(char('{'), p, char('}')).withContext(s"braces(${p.toString})")

  /** Turns a parser into one that consumes surrounding square brackets `[]` */
  def squareBrackets[A](p: Parser0[A]): Parser[A] =
    _bracket(char('['), p, char(']')).withContext(s"squareBrackets(${p.toString})")
}

object Literals {

  val stringLiteral: Parser[String] = {

    val lineTerminator: Parser[String] = (lf | cr | crlf).string

    val sourceCharacter: Parser[String] = (charIn(0x0009.toChar, 0x000A.toChar, 0x000D.toChar) | charIn(0x0020.toChar to 0xFFFF.toChar)).string

    val escapedUnicode: Parser[String] = string("\\u") *>
      hexdig
        .repExactlyAs[String](4)
        .map(hex => Integer.parseInt(hex, 16).toChar.toString)

    val escapedCharacter: Parser[String] = char('\\') *>
      (
        char('"').as("\"") |
          char('\\').as("\\") |
          char('/').as("/") |
          char('b').as("\b") |
          char('f').as("\f") |
          char('n').as("\n") |
          char('r').as("\r") |
          char('t').as("\t")
      )

    val stringCharacter: Parser[String] = (
      (peek(not(charIn('"', '\\') | lineTerminator)).with1 *> sourceCharacter) |
        escapedUnicode |
       escapedCharacter
    )

    lazy val blockStringCharacter = string("TODO").string

    //TODO should this be a Parser[Char] which converts to unicode? I guess so?
    //See Atto for reference




    stringCharacter.repAs0[String].with1.surroundedBy(char('"')) | (blockStringCharacter.rep0.string.with1.surroundedBy(string("\"\"\"")))

  }

  val intLiteral: Parser[Int] =
    bigInt.flatMap {
      case v if v.isValidInt => pure(v.toInt)
      case v => failWith(s"$v is larger than max int")
    }

  val booleanLiteral: Parser[Boolean] = string("true").as(true) | string("false").as(false)

  val floatLiteral: Parser[Double] = {

    val bigDecimal: Parser[BigDecimal] = (intLiteral ~ (char('.') *> digit.rep.string).? ~ ((char('e') | char('E')) *> intLiteral.string).?)
      .flatMap {
        case ((a, Some(b)), None) => pure(BigDecimal(s"$a.$b"))
        case ((a, None), Some(c)) => pure(BigDecimal(s"${a}E$c"))
        case ((a, Some(b)), Some(c)) => pure(BigDecimal(s"$a.${b}E$c"))
        case ((a, None), None) => failWith(s"$a is not a valid float - must have at least one of a fractional or exponent part")
      }

    //Unchecked narrowing
    bigDecimal.map(_.toDouble)
  }


}
