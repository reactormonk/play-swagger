package de.zalando.apifirst.generators

import de.zalando.apifirst.Application._
import de.zalando.apifirst.Domain._
import de.zalando.apifirst.ScalaName._
import de.zalando.apifirst.generators.DenotationNames.DenotationTable
import org.fusesource.scalate.{TemplateEngine, TemplateSource}

/**
  * @author slasch
  * @since 16.11.2015.
  */

class ScalaGenerator(val strictModel: StrictModel) extends PlayScalaControllerAnalyzer {

  val denotationTable = AstScalaPlayEnricher(strictModel)

  val StrictModel(modelCalls, modelTypes, modelParameters, discriminators, _) = strictModel

  val testsTemplateName           = "play_scala_test.mustache"
  val validatorsTemplateName      = "play_validation.mustache"
  val generatorsTemplateName      = "generators.mustache"
  val modelTemplateName           = "model.mustache"
  val controllersTemplateName     = "play_scala_controller.mustache"
  val controllerBaseTemplateName  = "play_scala_controller_base.mustache"


  def generate(fileName: String, currentController: String) = Seq(
    generateModel(fileName),
    generateGenerators(fileName),
    playValidators(fileName),
    playScalaControllerBases(fileName),
    playScalaTests(fileName),
    playScalaControllers(fileName, currentController)
  )

  def fieldsFromMap(a: Any): Seq[TemplateFieldData] = a match {
    case s: Seq[Map[String @unchecked, String @unchecked] @unchecked] =>
      s map { m => TemplateFieldData(m("name").toString, m("type_name").toString) }
    case other => throw new IllegalStateException("Expected Seq but was " + other)
  }

  def generateModel(fileName: String) = {
    val packages = TemplatePackageData(fileName.split('.').init.mkString("."), fileName.split('.').last)

    val bindings            = ReShaper.filterByType("bindings", denotationTable)
    val bindingsByType      = ReShaper.groupByType(bindings.toSeq).flatMap(_._2) map {
      case m: Map[String @unchecked, Any @unchecked] => TemplateBindingData(m("name").toString, m("format").asInstanceOf[Option[String]].getOrElse(""))
      case other => throw new IllegalStateException("Unrecognized binding")
    }

    val classes = ReShaper.filterByType("classes", denotationTable) map { m =>
      TemplateClassData(m("name").toString, fieldsFromMap(m("fields")), m("trait").asInstanceOf[Option[Map[String, String]]].map(_("name")))
    }
    val aliases = ReShaper.filterByType("aliases", denotationTable) map { m =>
      val tpe = m("underlying_type") match {
        case s: String => Some(s)
        case o: Option[String @unchecked] => o
      }
      TemplateAliasData(m("name").toString, m("alias").toString, tpe)
    }
    val traits = ReShaper.filterByType("traits", denotationTable) map { m =>
      TemplateTraitData(m("name").toString, fieldsFromMap(m("fields")))
    }
    val ast = shadowAst("")
    val imports = ast("imports").asInstanceOf[Seq[Map[String, String]]] map {_("name")}
    val bindingImports = ast("binding_imports").asInstanceOf[Seq[Map[String, String]]] map {_("name")}

    val data = TemplateModelData(packages, imports, bindingImports, aliases, traits, classes, bindingsByType)
    lazy val fullTemplate: String = _root_.txt.model.apply(data).body
    if (modelTypes.values.forall(_.isInstanceOf[PrimitiveType])) ""
    else fullTemplate
  }

  def generateGenerators(fileName: String) =
    if (modelTypes.isEmpty) ""
    else apply(fileName, generatorsTemplateName)

  def playValidators(fileName: String) =
    if (modelCalls.map(_.handler.parameters.size).sum == 0) ""
    else apply(fileName, validatorsTemplateName)

  def playScalaTests(fileName: String) =
    if (modelCalls.map(_.handler.parameters.size).sum == 0) ""
    else apply(fileName, testsTemplateName)

  def playScalaControllers(fileName: String, currentController: String) =
    if (modelCalls.isEmpty) ""
    else apply(fileName, controllersTemplateName, currentController)

  def playScalaControllerBases(fileName: String) =
    if (modelCalls.isEmpty) ""
    else apply(fileName, controllerBaseTemplateName)

  private def apply(fileName: String, templateName: String, currentController: String = ""): String = {
    val packages = Map(
      "main_package" -> fileName.split('.').map(escape).mkString("."),
      "main_package_prefix" -> fileName.split('.').init.mkString("."),
      "main_package_suffix" -> fileName.split('.').last,
      "spec_name" -> escape(capitalize("\\.", fileName) + "Spec")
    )
    nonEmptyTemplate(packages, templateName, currentController)
  }

  private def nonEmptyTemplate(map: Map[String, Any], templateName: String, currentController: String): String = {
    val engine = new TemplateEngine

    val allPackages: Map[String, Any] = shadowAst(currentController)

    val template            = getClass.getClassLoader.getResource(templateName)
    val templateSource      = TemplateSource.fromURL(template)
    val output              = engine.layout(templateSource, map ++ allPackages)

    output.replaceAll("\u2B90", "\n")
  }

  def shadowAst(currentController: String): Map[String, Any] = {
    val validations = ReShaper.filterByType("validators", denotationTable)
    val validationsByType = ReShaper.groupByType(validations.toSeq).toMap

    val bindings = ReShaper.filterByType("bindings", denotationTable)
    val bindingsByType = ReShaper.groupByType(bindings.toSeq).toMap

    val (unmanagedParts: Map[ApiCall, UnmanagedPart], unmanagedImports: Seq[String]) =
      analyzeController(currentController, denotationTable)

    val controllersMap = Map(
      "controllers" -> controllers(modelCalls, unmanagedParts)(denotationTable),
      "controller_imports" -> controllerImports.map(i => Map("name" -> i)),
      "unmanaged_imports" -> unmanagedImports.map(i => Map("name" -> i))
    )

    val singlePackage = Map(
      "classes" -> ReShaper.filterByType("classes", denotationTable),
      "aliases" -> ReShaper.filterByType("aliases", denotationTable),
      "traits" -> ReShaper.filterByType("traits", denotationTable),
      "test_data_classes" -> ReShaper.filterByType("test_data_classes", denotationTable),
      "test_data_aliases" -> ReShaper.filterByType("test_data_aliases", denotationTable),
      "tests" -> ReShaper.filterByType("tests", denotationTable),
      "bindings" -> bindingsByType
    )

    val rawAllPackages = singlePackage ++ validationsByType ++ controllersMap
    val allPackages = enrichWithStructuralInfo(rawAllPackages)
    allPackages
  }

  def enrichWithStructuralInfo(rawAllPackages: Map[String, Iterable[Any]]): Map[String, Any] = {
    val imports = KeyCollector.collect("imports")(rawAllPackages)
    val importMaps = imports.distinct map { i => Map("name" -> i) }

    val bind_imports = KeyCollector.collect("binding_imports")(rawAllPackages)
    val bind_importMaps = bind_imports.distinct map { i => Map("name" -> i) }

    val allPackages = LastListElementMarks.set(rawAllPackages) ++
      neededParts(imports) + ("imports" -> importMaps) + ("binding_imports" -> bind_importMaps)
    allPackages
  }

  private val partsMapping = Map(
    "lists?"      -> "ArrayWrapper",
    "maps?"       -> "Map",
    "date?"       -> "DateMidnight",
    "date_time?"  -> "DateTime"
  )
  private def neededParts(imports: Seq[String]): Map[String, Boolean] = partsMapping map {
    case (k,v) => k -> imports.exists(_.contains(v))
  }
}

trait PlayScalaControllerAnalyzer extends PlayScalaControllersGenerator with ControllersCommons {

  def strictModel: StrictModel

  def analyzeController(currentVersion: String, table: DenotationTable): (Map[ApiCall, UnmanagedPart], Seq[String]) = {
    val markers = generateMarkers(strictModel.calls, table)
    val allLines = currentVersion.split("\n").filter(_.trim.nonEmpty).dropWhile(_.trim.isEmpty).toSeq
    val classPositions = allLines.zipWithIndex.filter(_._1.trim.startsWith("class")).map(_._2) :+ (allLines.length + 1)
    val classBlocks = if (classPositions.length > 1) classPositions.sliding(2).toSeq else Seq.empty
    val lines = classBlocks flatMap { b => allLines.slice(b.head, b(1) - 3) }
    val cleanLines = lines map {
      _.trim
    }
    val markerIndexes = markers map { marker =>
      cleanLines.indexOf(marker._2._1)
    }
    val sortedMarkers = markerIndexes.sorted
    val codeParts = markerIndexes map { idx => sortedMarkers.find(_ > idx).getOrElse(lines.length) }

    val parts = markers zip markerIndexes zip codeParts filter {
      case ((((call, (marker, length)), start), end)) => start >= 0
    } map {
      case ((((call, (marker, length)), start), end)) =>
        val code = lines.slice(start + length, end - 1)
        val relevantCode = code.takeWhile(!_.contains(eof))
        val deadCode = code.dropWhile(!_.contains(eof)).drop(1).filterNot(_.trim.isEmpty)
        val commentedOut = if (deadCode.isEmpty) "" else deadCode.mkString("/*\n", "\n", "\n*/\n")
        call -> UnmanagedPart(call, relevantCode.mkString("\n"), commentedOut)
    }
    val preservedImports =
      allLines.filter(_.trim.startsWith("import")).map(_.replace("import ", "")).filterNot(controllerImports.contains)
    (parts.toMap, preservedImports)
  }

  def generateMarkers(allCalls: Seq[ApiCall], table: DenotationTable) = {
    val markers = allCalls map { call =>
      val controllerDenotations = table(call.asReference)("controller")
      val signature = controllerDenotations("signature")
      val markerSize = // FIXME this will fail if parameter types will change
        if (controllerDenotations.contains("parameters?")) 3
        else if (controllerDenotations.contains("single_parameter?")) 2
        else 1
      call -> (signature, markerSize)
    }
    markers
  }
}

trait PlayScalaControllersGenerator {

  val controllerImports = Seq(
    "play.api.mvc.{Action, Controller}",
    "play.api.data.validation.Constraint",
    "de.zalando.play.controllers._",
    "PlayBodyParsing._",
    "PlayValidations._"
  )

  case class UnmanagedPart(marker: ApiCall, relevantCode: String, deadCode: String)

  val baseControllersSuffix = "Base"

  def controllers(allCalls: Seq[ApiCall], unmanagedParts: Map[ApiCall, UnmanagedPart])(table: DenotationTable) = {
    allCalls groupBy { c =>
      (c.handler.packageName, c.handler.controller)
    } map { case (controller, calls) =>
      val methods = calls map { singleMethod(unmanagedParts, table) }
      Map(
        "effective_package"   -> escape(controller._1), // TODO currently, the package name just ignored
        "controller"          -> escape(controller._2),
        "base"                -> escape(controller._2 + baseControllersSuffix),
        "methods"             -> methods
      )
    }
  }

  def singleMethod(unmanagedParts: Map[ApiCall, UnmanagedPart], table: DenotationTable): ApiCall => Map[String, Any] =
    call => {
      val method = table(call.asReference)("controller")
      val methodWithCode = method + (
        "dead_code"       -> unmanagedParts.get(call).map(_.deadCode).getOrElse(""),
        "implementation"  -> unmanagedParts.get(call).map(_.relevantCode).getOrElse("???")
        )
      methodWithCode
    }
}
