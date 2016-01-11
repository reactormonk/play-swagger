package de.zalando.apifirst.generators

/**
  * @author slasch 
  * @since 11.01.2016.
  */
case class TemplatePackageData(mainPackagePrefix: String, mainPackageSuffix: String)

case class TemplateAliasData(name: String, alias: String, underlyingType: Option[String]) {
  def underlyingTypeStr = underlyingType.fold("")("[" + _ + "]")
}

case class TemplateFieldData(name: String, typeName: String)

case class TemplateTraitData(name: String, fields: Iterable[TemplateFieldData])

case class TemplateClassData(name: String, fields: Iterable[TemplateFieldData], extending: Option[String])

case class TemplateBindingData(name: String, format: String)

case class TemplateModelData(
  packages: TemplatePackageData,
  imports: Iterable[String],
  bindingImports: Seq[String],
  aliases: Iterable[TemplateAliasData],
  traits: Iterable[TemplateTraitData],
  classes: Iterable[TemplateClassData],
  bindings: Iterable[TemplateBindingData]
  )
