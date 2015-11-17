package de.zalando.apifirst

import de.zalando.apifirst.Application._
import de.zalando.apifirst.Domain._
import de.zalando.apifirst.new_naming.Reference

import scala.annotation.tailrec
import scala.language.reflectiveCalls

/**
  * Flattens types by recursively replacing nested type definitions with references
  *
  * This implementation relies on {@ParameterDereferencer} to extract all non-primitive types from parameter definitions
  *
  * Because of that, the {@ParameterDereferencer} MUST be applied before using {@TypeFlattener},
  * otherwise results will be inconsistent parameter definitions
  *
  * @author  slasch 
  * @since   11.11.2015.
  */
object TypeFlattener extends TypeAnalyzer {

  def apply(app: StrictModel): StrictModel = {
    val flatTypeDefs = flatten0(app.typeDefs)
    app.copy(typeDefs = flatTypeDefs)
  }

  private def flatten0(typeDefs: TypeLookupTable): TypeLookupTable = {
    val flatTypeDefs = typeDefs flatMap { case (k, v) => extractComplexType(k, v) }
    if (flatTypeDefs == typeDefs)
      flatTypeDefs
    else
      flatten0(flatTypeDefs)
  }

  private def extractComplexType(ref: Reference, typeDef: Type): Seq[(Reference, Type)] = typeDef match {
    case t: TypeDef if complexFields(t).nonEmpty =>
      val (changedFields, extractedTypes) = t.fields.filter(complexField).map(createTypeFromField(t)).unzip
      val newFields = t.fields.filterNot(complexField) ++ changedFields
      val newTypeDef = t.copy(fields = newFields)
      (ref -> newTypeDef) +: extractedTypes
    case c: Container if isComplexType(c.tpe) =>
      val newRef = ref / c.getClass.getSimpleName
      Seq(ref -> c.withType(TypeReference(newRef)), newRef -> c.tpe)
    case c: Composite =>
      val (changedTypes, extractedTypes) = c.descendants.filter(isComplexType).
        zipWithIndex.map(flattenType(c.getClass.getSimpleName, ref)).unzip
      val newTypes = c.descendants.filterNot(isComplexType) ++ changedTypes
      val newTypeDef = c.withTypes(newTypes)
      (ref -> newTypeDef) +: extractedTypes
    case _ => Seq(ref -> typeDef)
  }

  private def complexFields(typeDef: TypeDef): Seq[Field] = typeDef.fields filter complexField

  private def complexField: (Field) => Boolean = f => isComplexType(f.tpe)

  private def createTypeFromField(t: TypeDef): (Field) => (Field, (Reference, Type)) = field => {
    val newReference = TypeReference(t.name / field.name.simple)
    val extractedType = field.tpe
    (field.copy(tpe = newReference), newReference.name -> extractedType)
  }

  private def flattenType: (String, Reference) => ((Type, Int)) => (Type, (Reference, Type)) = (name, ref) => pair => {
    val (typeDef, index) = pair
    val newReference = TypeReference(ref / (name + index))
    (newReference, newReference.name -> typeDef)
  }


}

object TypeDeduplicator extends TypeAnalyzer {

  /**
    * Removes redundant type definitions changing pointing references
    *
    * @param app
    * @return
    */
  def apply(app: StrictModel): StrictModel = {
    val types = app.typeDefs.values
    val duplicate = types find { t => types.count(isSameTypeDef(t)) > 1 }
    duplicate map replaceSingle(app) map apply getOrElse app
  }

  private def replaceSingle(model: StrictModel): Type => StrictModel = tpe => {
    val duplicates = model.typeDefs.filter { d => isSameTypeDef(tpe)(d._2) }
    val duplicateNames = sortByDiscriminatorOrPathLength(model.discriminators, duplicates)
    val bestMatch :: refsToRemove = duplicateNames
    val typesToRewrite = model.typeDefs filterNot { t => refsToRemove.contains(t._1) }
    val typesWithCorrectRefs = typesToRewrite map { d => replaceReferencesInTypes(refsToRemove, bestMatch)(d._1, d._2) }
    val newParams = model.params map replaceReferenceInParameter(refsToRemove, bestMatch)
    model.copy(typeDefs = typesWithCorrectRefs, params = newParams)
  }


  private def replaceReferencesInTypes(duplicateRefs: Seq[Reference], target: Reference):
  (Reference, Type) => (Reference, Type) = (ref, tpe) => ref -> {
    tpe match {
      case c: Container =>
        c.tpe match {
          case r: TypeReference if duplicateRefs.contains(r.name) => c.withType(TypeReference(target))
          case o => c
        }

      case c: Composite =>
        val newDescendants = c.descendants map {
          case d: TypeReference if duplicateRefs.contains(d.name) => TypeReference(target)
          case o => o
        }
        c.withTypes(newDescendants)

      case t: TypeDef =>
        val newFields = t.fields.map {
          case f@Field(_, tpe: TypeReference) if duplicateRefs.contains(tpe.name) => f.copy(tpe = TypeReference(target))
          case o => o
        }
        t.copy(fields = newFields)

      case TypeReference(name) if duplicateRefs.contains(name) => TypeReference(target)

      case _ => tpe
    }
  }

  private def replaceReferenceInParameter(duplicateRefs: Seq[Reference], target: Reference):
  ((ParameterRef, Parameter)) => (ParameterRef, Parameter) = {
    case (r: ParameterRef, p: Parameter) if duplicateRefs.contains(p.typeName.name) =>
      r -> p.copy(typeName = TypeReference(target))
    case o => o
  }

  private def sortByDiscriminatorOrPathLength(discriminators: DiscriminatorLookupTable,
                                              duplicates: TypeLookupTable): List[Reference] =
    duplicates.toSeq.sortBy { p =>
      val factor = if (discriminators.keySet.contains(p._2.name)) 1 else 1000
      p._1.pointer.tokens.size * factor
    }.map(_._1).toList

}

object ParameterDereferencer extends TypeAnalyzer {
  /**
    * Converts inline type definitions into type references
    * @param app
    * @return
    */
  @tailrec
  def apply(app: StrictModel): StrictModel = {
    var result = app
    result.params foreach { case (name, definition) =>
      definition.typeName match {
        case tpe if isComplexType(tpe) =>
          val newName = name.name / "ref"
          val newReference = TypeReference(newName)
          val tps = app.typeDefs + (newName -> tpe)
          val newParams = app.params.updated(name, definition.copy(typeName = newReference))
          result = result.copy(typeDefs = tps, params = newParams)
        case _ =>
      }
    }
    if (result == app) result else apply(result)
  }
}

trait TypeAnalyzer {
  def isComplexType(t: Type): Boolean = t match {
    case tpe@(_: TypeDef | _: Composite | _: Container) => true
    case _ => false
  }

  def isSameTypeDef(one: Type)(two: Type): Boolean = (one, two) match {
    case (c1: Container, c2: Container) =>
      isSameTypeDef(c1.tpe)(c2.tpe)
    case (c1: Composite, c2: Composite) if c1.descendants.size == c2.descendants.size =>
      sameDescendants(c1, c2)
    case (t1: TypeDef, t2: TypeDef) if t1.fields.size == t2.fields.size =>
      sameFields(t1, t2)
    case (t1: ProvidedType, t2: ProvidedType) if t1.getClass == t2.getClass =>
      sameNames(t1, t2)
    case (r1: TypeReference, r2: TypeReference) =>
      r1.name == r2.name
    case _ => false
  }

  def sameFields(t1: TypeDef, t2: TypeDef): Boolean =
    t1.fields.forall(p => t2.fields.exists(e => isSameTypeDef(p.tpe)(e.tpe) && sameNames(p, e)))

  type hasSimpleName = {def name: {def simple: String}}

  def sameNames(p: hasSimpleName, e: hasSimpleName): Boolean =
    p.name.simple == e.name.simple

  def sameDescendants(c1: Composite, c2: Composite): Boolean =
    c1.descendants.forall(p => c2.descendants.exists(isSameTypeDef(p)))

}