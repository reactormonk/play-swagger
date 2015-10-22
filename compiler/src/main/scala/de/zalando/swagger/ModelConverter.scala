package de.zalando.swagger

import de.zalando.apifirst.Application.StrictModel
import de.zalando.apifirst.Domain.Type
import de.zalando.swagger.strictModel.SwaggerModel

/**
  * @author  slasch 
  * @since   20.10.2015.
  */
trait ParameterNaming {
  val PATH_SEPARATOR = "/"
  def append(prefix: String, suffix: String) = prefix + PATH_SEPARATOR + suffix
  def pathPrefix(name: String) = {
    val parts = name.split(PATH_SEPARATOR)
    (parts.init.mkString("/"), parts.last)
  }
  def simple(name: String) = name.split(PATH_SEPARATOR).lastOption
  type Types = Seq[Type]
  type NamedType = (String, Type)
  type NamedTypes = Seq[NamedType]
}
trait StringUtil {
  def capitalize(separator: String, str: String) = {
    assert(str != null)
    str.split(separator).map { p => if (p.nonEmpty) p.head.toUpper +: p.tail else p }.mkString("")
  }
  def camelize(separator: String, str: String) = capitalize(separator, str) match {
    case p if p.isEmpty => ""
    case p => p.head.toLower +: p.tail
  }
}
object ModelConverter extends ParameterNaming {

  def fromModel(model: SwaggerModel, keyPrefix: String = "x-api-first") = {
    val typeDefs = TypeConverter.fromModel(model)
    val apiCalls = new PathsConverter(keyPrefix, model, typeDefs).convert
    StrictModel(apiCalls, typeDefs.toMap)
  }

}
