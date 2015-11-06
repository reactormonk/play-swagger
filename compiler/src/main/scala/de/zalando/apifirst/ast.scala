package de.zalando.apifirst

import de.zalando.apifirst.Http.MimeType
import de.zalando.apifirst.new_naming.{Pointer, Pointer$, TypeName, Reference}
import de.zalando.swagger.DiscriminatorLookupTable

import scala.language.{implicitConversions, postfixOps}
import scala.util.parsing.input.Positional

sealed trait Expr

object Http {

  abstract class Verb(val name: String) extends Expr

  case object GET     extends Verb("GET")
  case object POST    extends Verb("POST")
  case object PUT     extends Verb("PUT")
  case object DELETE  extends Verb("DELETE")
  case object HEAD    extends Verb("HEAD")
  case object OPTIONS extends Verb("OPTIONS")
  case object TRACE   extends Verb("TRACE")
  case object PATCH   extends Verb("PATCH")

  private val verbs = GET :: POST :: PUT :: DELETE :: HEAD :: OPTIONS :: TRACE :: PATCH :: Nil

  def string2verb(name: String): Option[Verb] = verbs find { _.name == name.trim.toUpperCase }

  // TODO flesh out this hierarchy
  class MimeType(val name: String) extends Expr

  case object ApplicationJson extends MimeType("application/json")

  case class Body(value: Option[String]) extends Expr

}

object Hypermedia {

  // TODO relation should be another API call, not an url
  case class Relation(tpe: String, url: String)

}

object Domain {

  type ModelDefinition = Iterable[Domain.Type]

  case class TypeMeta(comment: Option[String], constraints: Seq[String]) {
    override def toString = s"""TypeMeta($escapedComment, $constraints)"""

    val escapedComment = comment match {
      case None => "None"
      case Some(s) => "Some(\"" + s.replaceAll("\"", "\\\"") + "\")"
    }
  }

  object TypeMeta {
    def apply(comment: Option[String]): TypeMeta = TypeMeta(comment, Seq.empty[String])
  }

  implicit def option2TypeMeta(o: Option[String]): TypeMeta = TypeMeta(o)

  abstract class Type(val name: TypeName, val meta: TypeMeta) extends Expr {
    def nestedTypes: Seq[Type] = Nil
    def imports: Set[String] = Set.empty
    def toShortString(pad: String) = getClass.getSimpleName
  }

  case class TypeReference(pointer: Pointer) extends Type(pointer.toString, TypeMeta(None)) with Reference {
    override def toShortString(pad: String) = s"${super.toShortString(pad)}(${pointer.toString})"
  }

  class Nmbr(override val name: TypeName, override val meta: TypeMeta) extends Type(name, meta)

  case class Intgr(override val meta: TypeMeta) extends Nmbr("/Int", meta)

  case class Lng(override val meta: TypeMeta) extends Nmbr("/Long", meta)

  case class Flt(override val meta: TypeMeta) extends Nmbr("/Float", meta)

  case class Dbl(override val meta: TypeMeta) extends Nmbr("/Double", meta)

  case class Byt(override val meta: TypeMeta) extends Nmbr("/Byte", meta)

  case class Str(format: Option[String] = None, override val meta: TypeMeta) extends Type("/String", meta)

  case class Bool(override val meta: TypeMeta) extends Type("/Boolean", meta)

  case class Date(override val meta: TypeMeta) extends Type("/Date", meta) {
    override val imports = Set("java.util.Date")
  }

  case class File(override val meta: TypeMeta) extends Type("/File", meta) {
    override val imports = Set("java.io.File")
  }

  case class DateTime(override val meta: TypeMeta) extends Type("/Date", meta) {
    override val imports = Set("java.util.Date")
  }

  case class Password(override val meta: TypeMeta) extends Type("/Password", meta)

  case class Null(override val meta: TypeMeta) extends Type("/null", meta)

  case class Any(override val meta: TypeMeta) extends Type("/Any", meta)

  /**
   * The Composite describes the class composition
   *
   * It is different from the Container because it
   * has more than one underlying Type
   *
   * @param name
   * @param meta
   * @param allOf
   */
  case class Composite(override val name: TypeName, override val meta: TypeMeta, allOf: Seq[Type])
    extends Type(name, meta) {
    override def toShortString(pad: String) = s"${getClass.getSimpleName}(${allOf.map(_.toShortString(pad+"\t")).mkString(s"\n$pad",s"\n$pad","")})"
    override def nestedTypes = allOf flatMap ( _.nestedTypes )
    override def imports: Set[String] = allOf.flatMap(_.imports).toSet
  }

  abstract class Container(override val name: TypeName, val tpe: Type, override val meta: TypeMeta, override val imports: Set[String])
    extends Type(name, meta) {
    def allImports: Set[String] = imports ++ tpe.imports
    override def nestedTypes = tpe.nestedTypes :+ tpe
    override def toShortString(pad: String) = s"${getClass.getSimpleName}(${tpe.toShortString(pad)})"
  }

  case class Arr(override val tpe: Type, override val meta: TypeMeta, format: Option[String] = None)
    extends Container(s"${tpe.name.parent}", tpe, meta, Set("scala.collection.Seq"))

  case class Opt(override val tpe: Type, override val meta: TypeMeta)
    extends Container(s"${tpe.name.parent}", tpe, meta, Set("scala.Option"))

  case class CatchAll(override val tpe: Type, override val meta: TypeMeta)
    extends Container(s"${tpe.name.parent}", tpe, meta, Set("scala.collection.immutable.Map"))

  case class Field(name: TypeName, tpe: Type) {
    def toString(pad: String) = s"""\n${pad}Field("$name", ${tpe.toShortString(pad+"\t")})"""

    def imports = tpe match {
      case c: Container => c.allImports
      case o => o.imports
    }

    def nestedTypes = tpe.nestedTypes :+ tpe
  }

  case class TypeDef(override val name: TypeName,
                     fields: Seq[Field],
                     override val meta: TypeMeta) extends Type(name, meta) {
    override def toString = s"""\n\tTypeDef("$name", \n\t\tSeq(${fields.mkString("\n\t\t\t", ",\n\t\t\t", "")}\n\t\t), $meta)\n"""
    override def toShortString(pad: String) = s"""TypeDef("$name", Seq(${fields.map(_.toString(pad)).mkString(", ")}))"""

    override def nestedTypes = fields flatMap (_.nestedTypes) filter { _.name.parent == name  } distinct
  }

}

object Path {

  import scala.language.{implicitConversions, postfixOps}

  abstract class PathElem(val value: String) extends Expr

  case object Root extends PathElem(value = "/")

  case class Segment(override val value: String) extends PathElem(value)

  case class InPathParameter(name: Reference) extends PathElem(name.simple)

  case class FullPath(value: Seq[PathElem]) extends Expr {
    def isAbsolute = value match {
      case Root :: segments => true
      case _ => false
    }
    override val toString = string { p: InPathParameter => "{" + p.value + "}" }

    val camelize = string("by/" + _.value).split("/") map { p =>
      if (p.nonEmpty) p.head.toUpper +: p.tail else p
    } mkString ""

    def string(inPath2Str: InPathParameter => String) = {
      value match {
        case Nil => ""
        case Root :: Nil => "/"
        case other => other map {
          case Root => ""
          case Segment(v) => v
          case i: InPathParameter => inPath2Str(i)
        } mkString "/"
      }
    }

  }

  object FullPath {
    def is(elements: PathElem*): FullPath = FullPath(elements.toList)
  }

  def path2path(path: String, parameters: Seq[Application.ParameterRef]): FullPath = {
    def path2segments(path: String, parameters: Seq[Application.ParameterRef]) = {
      path.trim split Root.value map {
        case seg if seg.startsWith("{") && seg.endsWith("}") =>
          val name = seg.tail.init
          parameters.find(_.simple == name) map { p => InPathParameter(p.name) }
        case seg if seg.nonEmpty =>
          Some(Segment(seg))
        case seg if seg.isEmpty =>
          Some(Root)
      }
    }
    val segments = path2segments(path, parameters).toList.flatten
    val elements = if (path.endsWith("/")) segments :+ Root else segments
    FullPath(elements)
  }

}

case object ParameterPlace extends Enumeration {
  type ParameterPlace = Value
  val PATH    = Value("path")
  val BODY    = Value("body")
  val FORM    = Value("formData")
  val QUERY   = Value("query")
  val HEADER  = Value("header")
}

object Application {

  case class ParameterRef(name: Reference) {
    val simple = name.simple
  }

  // Play definition
  case class Parameter(
    name:             String,
    typeName:         Domain.Type,
    fixed:            Option[String],
    default:          Option[String],
    constraint:       String,
    encode:           Boolean,
    place:            ParameterPlace.Value
  ) extends Expr with Positional

  case class HandlerCall(
    packageName:      String,
    controller:       String,
    instantiate:      Boolean,
    method:           String,
    parameters:       Seq[ParameterRef]
  ) {
/*
    val nonBodyParameters     = params.filterNot(_.place == ParameterPlace.BODY)
    val bodyParameters        = params.filter(_.place == ParameterPlace.BODY)
    val queryParameters       = params.filter(_.place == ParameterPlace.QUERY)
*/
  }

  case class ApiCall(
    verb:             Http.Verb,
    path:             Path.FullPath,
    handler:          HandlerCall,
    mimeIn:           Set[MimeType],  // can be empty for swagger specification
    mimeOut:          Set[MimeType],  // can be empty for swagger specification
    errorMapping:     Map[String, Seq[Class[Exception]]], // can be empty for swagger specification
    resultTypes:      Iterable[ParameterRef]
  )

  case class Model(
    calls:            Seq[ApiCall],
    definitions:      Iterable[Domain.Type]
  )

  type ParameterLookupTable     = Map[ParameterRef, Parameter]
  type DiscriminatorLookupTable = Map[Reference, String]

  case class StrictModel(calls: Seq[ApiCall], typeDefs: Map[Reference, Domain.Type], params: ParameterLookupTable, discriminators: DiscriminatorLookupTable) {
    def findParameter(ref: ParameterRef): Parameter = params(ref)
    def findParameter(name: Reference): Option[Parameter] = params.find(_._1.name == name).map(_._2)
  }

}

