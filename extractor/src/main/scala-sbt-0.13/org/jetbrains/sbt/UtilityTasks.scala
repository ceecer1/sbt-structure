package org.jetbrains.sbt

import java.io.{BufferedWriter, FileOutputStream, OutputStreamWriter}

import sbt._
import extractors.SettingKeys
import Def.Initialize
import structure.XmlSerializer._

import scala.language.reflectiveCalls
import scala.xml._

/**
 * @author Nikolay Obedin
 */
object UtilityTasks extends SbtStateOps {

  def dumpStructure: Initialize[Task[Unit]] = Def.task {
    val structure = StructureKeys.extractStructure.value
    val options = StructureKeys.sbtStructureOpts.value
    val outputFile = StructureKeys.sbtStructureOutputFile.value
    val log = Keys.streams.value.log

    val outputText = {
      if (options.prettyPrint)
        new PrettyPrinter(180, 2).format(structure.serialize)
      else
        xml.Utility.trim(structure.serialize).mkString
    }

    outputFile.map { file =>
      log.info("Writing structure to " + file.getPath + "...")
      // noinspection UnitInMap
      writeToFile(file, outputText)
    } getOrElse {
      log.info("Writing structure to console:")
      println(outputText)
    }
    log.info("Done.")
  }

  def acceptedProjects: Initialize[Task[Seq[ProjectRef]]] = Keys.state.map { state =>
    structure(state).allProjectRefs.filter { case ref@ProjectRef(_, id) =>
      val isProjectAccepted = structure(state).allProjects.find(_.id == id).exists(areNecessaryPluginsLoaded)
      val shouldSkipProject =
        SettingKeys.ideSkipProject.in(ref).getOrElse(state, false) ||
          SettingKeys.sbtIdeaIgnoreModule.in(ref).getOrElse(state, false)
      isProjectAccepted && !shouldSkipProject
    }
  }

  def allConfigurationsWithSource: Def.Initialize[Seq[Configuration]] = Def.settingDyn {
    val cs = for {
      c <- Keys.ivyConfigurations.value
    } yield (Keys.sourceDirectories in c).?.apply { filesOpt => filesOpt.flatMap(f => f.nonEmpty.option(c))}

    cs.foldLeft(Def.setting(Seq.empty[Configuration])) { (accDef, initOptConf) =>
      accDef.zipWith(initOptConf) {(acc, optConf) => acc ++ optConf.toSeq }
    }
  }

  def testConfigurations: Def.Initialize[Seq[Configuration]] = allConfigurationsWithSource.apply { cs =>
    val predefinedTest = Set(Test, IntegrationTest)
    val transitiveTest = cs.filter(c =>
      transitiveExtends(c.extendsConfigs)
        .toSet
        .intersect(predefinedTest).nonEmpty) ++
    predefinedTest
    transitiveTest.distinct
  }

  def sourceConfigurations: Def.Initialize[Seq[Configuration]] = Def.setting {
    (allConfigurationsWithSource.value.diff(testConfigurations.value) ++ Seq(Compile)).distinct
  }

  def dependencyConfigurations: Def.Initialize[Seq[Configuration]] =
    allConfigurationsWithSource.apply(cs => (cs ++ Seq(Runtime, Provided, Optional)).distinct)

  def classifiersModuleRespectingStructureOpts: Initialize[Task[GetClassifiersModule]] = Def.task {
    val module = (Keys.classifiersModule in Keys.updateClassifiers).value
    val options = StructureKeys.sbtStructureOpts.value
    if (options.resolveJavadocs) {
      module
    } else {
      val classifiersWithoutJavadocs = module.classifiers.filterNot(_ == Artifact.DocClassifier)
      module.copy(classifiers = classifiersWithoutJavadocs)
    }
  }

  private def areNecessaryPluginsLoaded(project: ResolvedProject): Boolean = {
    // Here is a hackish way to test whether project has JvmPlugin enabled.
    // Prior to 0.13.8 SBT had this one enabled by default for all projects.
    // Now there may exist projects with IvyPlugin (and thus JvmPlugin) disabled
    // lacking all the settings we need to extract in order to import project in IDEA.
    // These projects are filtered out by checking `autoPlugins` field.
    // But earlier versions of SBT 0.13.x had no `autoPlugins` field so
    // structural typing is used to get the data.
    try {
      type ResolvedProject_0_13_7 = {def autoPlugins: Seq[{ def label: String}]}
      val resolvedProject_0_13_7 = project.asInstanceOf[ResolvedProject_0_13_7]
      val labels = resolvedProject_0_13_7.autoPlugins.map(_.label)
      labels.contains("sbt.plugins.JvmPlugin")
    } catch {
      case _ : NoSuchMethodException => true
    }
  }

  private def writeToFile(file: File, xml: String) {
    val writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file), "UTF-8"))
    try {
      writer.write("""<?xml version="1.0" encoding="UTF-8"?>""")
      writer.newLine()
      writer.write(xml)
      writer.flush()
    } finally {
      writer.close()
    }
  }
}
