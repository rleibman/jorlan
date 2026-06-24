////////////////////////////////////////////////////////////////////////////////////
// Common Stuff

import org.apache.commons.io.FileUtils
import scalajs.esbuild.ScalaJSEsbuildPlugin.autoImport.*
import scalajs.esbuild.web.ScalaJSEsbuildWebPlugin
import sbtcrossproject.CrossProject

import scala.concurrent.duration.*

lazy val buildTime: SettingKey[String] = SettingKey[String]("buildTime", "time of build").withRank(KeyRanks.Invisible)

//////////////////////////////////////////////////////////////////////////////////////////////////
// Global stuff
ThisBuild / resolvers += Resolver.sonatypeCentralSnapshots
ThisBuild / resolvers += "GitHub Packages rleibman/zio-auth" at "https://maven.pkg.github.com/rleibman/zio-auth"
ThisBuild / resolvers += "GitHub Packages rleibman/jorlan" at "https://maven.pkg.github.com/rleibman/jorlan"
ThisBuild / credentials += Credentials(
  "GitHub Package Registry",
  "maven.pkg.github.com",
  sys.env.getOrElse("GITHUB_ACTOR", "rleibman"),
  sys.env.getOrElse("GITHUB_TOKEN", ""),
)

lazy val SCALA = "3.8.4"
Global / onChangedBuildSource := ReloadOnSourceChanges
scalaVersion                  := SCALA
Global / scalaVersion         := SCALA

Global / watchAntiEntropy := 1.second

ThisBuild / libraryDependencySchemes += "dev.zio" %% "zio-json" % VersionScheme.Always

//////////////////////////////////////////////////////////////////////////////////////////////////
// Shared settings

lazy val start = TaskKey[Unit]("start")
lazy val dist = TaskKey[File]("dist")
lazy val debugDist = TaskKey[File]("debugDist")

def webDistImpl(
  assets:       File,
  bundleOutput: File,
  outputFolder: File,
): File = {
  outputFolder.mkdirs()
  // Copy static assets, skipping index.html (that comes from esbuild output)
  if (assets.exists()) {
    assets.listFiles().foreach { f =>
      if (f.getName != "index.html") {
        if (f.isDirectory) FileUtils.copyDirectory(f, outputFolder / f.getName, true)
        else FileUtils.copyFile(f, outputFolder / f.getName)
      }
    }
  }
  if (bundleOutput.exists()) {
    println(s"Copying esbuild output from: $bundleOutput")
    FileUtils.copyDirectory(bundleOutput, outputFolder, true)
  } else {
    println(s"esbuild output directory does not exist: $bundleOutput")
  }
  outputFolder
}

lazy val scala3Opts = Seq(
  "-Wconf:msg=Implicit parameters should be provided with a `using` clause:s",
  "-deprecation", // Emit warning and location for usages of deprecated APIs.
  "-no-indent", // scala3
  "-old-syntax", // I hate space sensitive languages!
  "-encoding",
  "utf-8", // Specify character encoding used by source files.
  "-feature", // Emit warning and location for usages of features that should be imported explicitly.
  "-language:existentials", // Existential types (besides wildcard types) can be written and inferred
  "-language:implicitConversions",
  "-language:higherKinds", // Allow higher-kinded types
  //  "-language:strictEquality", //This is cool, but super noisy
  "-unchecked", // Enable additional warnings where generated code depends on assumptions.
  //  "-Wsafe-init", //Great idea, breaks compile though.
  "-Xmax-inlines",
  "128",
  //  "-explain-types", // Explain type errors in more detail.
  //  "-explain",
  "-Yexplicit-nulls", // Make reference types non-nullable. Nullable types can be expressed with unions: e.g. String|Null.
  "-Yretain-trees", // Retain trees for debugging.,
)

enablePlugins(
  com.github.sbt.git.GitVersioning,
)

val emilVersion = "0.20.0"
val zioInteropCatsVersion = "23.1.0.13"
val bouncyCastleVersion = "1.84"
val googleApiClientVersion = "2.9.0"
val googleApisGmailVersion = "v1-rev20260525-2.0.0"
val googleApisCalendarVersion = "v3-rev20260614-2.0.0"
val googleApisDriveVersion = "v3-rev20260428-2.0.0"
val googleApisPeopleVersion = "v1-rev20251117-2.0.0"
val googleAuthLibraryVersion = "1.48.0"
val telegramiumVersion = "10.1000.0"
val calibanClientVersion = "3.1.2"
val calibanVersion = "3.1.2"
val commonsCodecVersion = "1.21.0"
val courierVersion = "4.0.0-RC1"
val cron4sVersion = "0.8.2"
val dispatchHttpVersion = "2.0.0"
val flywayVersion = "12.9.0"
val izumiReflectVersion = "3.0.9"
val jaxbApiVersion = "2.3.1"
val jsoniterVersion = "2.38.16"
val justSemverCoreVersion = "1.3.0"
val jwtCirceVersion = "11.0.4"
val jwtZioJsonVersion = "11.0.4"
val langchain4jOllamaVersion = "1.16.3"
val langchainCoreVersion = "1.16.3"
val langchainLibrariesVersion = "1.16.3-beta26"
val lanternaVersion = "3.1.5"
val logbackVersion = "1.5.34"
val mariadbVersion = "3.5.9"
val openPdfVersion = "3.0.3"
val qdrantVersion = "1.21.4"
val quillVersion = "4.8.6"
val scalablytypedRuntimeVersion = "2.4.2"
val scalacssVersion = "1.0.0"
val scalaJavaTimeVersion = "2.7.0"
val scalajsDomVersion = "2.8.1"
val scalajsReactVersion = "4.0.0"
val scalatagsVersion = "0.13.1"
val stlibVersion = "1.3.0"
val sttpClient4Version = "4.0.25"
val testContainerVersion = "0.44.1"
val zioAuth = "3.1.6"
val zioCacheVersion = "0.2.8"
val zioConfigVersion = "4.0.7"
val zioHttpVersion = "3.11.2"
val zioJsonVersion = "0.9.2"
val zioLoggingSlf4j2Version = "2.5.3"
val zioNioVersion = "2.0.2"
val zioPreludeVersion = "1.0.0-RC47"
val zioProcessVersion = "0.8.0"
val zioVersion = "2.1.26"

lazy val commonSettings = Seq(
  organization     := "net.leibman",
  startYear        := Some(2026),
  organizationName := "Roberto Leibman",
  headerLicense := Some(HeaderLicense.ALv2("2026", "Roberto Leibman", HeaderLicenseStyle.SpdxSyntax)),
  licenses += ("Apache-2.0", url("https://www.apache.org/licenses/LICENSE-2.0.txt")),
  resolvers += Resolver.mavenLocal,
)

////////////////////////////////////////////////////////////////////////////////////
// Model
lazy val modelJVM = model.jvm
lazy val modelJS = model.js

lazy val model =
  crossProject(JSPlatform, JVMPlatform)
    .enablePlugins(
      AutomateHeaderPlugin,
      com.github.sbt.git.GitVersioning,
      BuildInfoPlugin,
    )
    .settings(
      name             := "jorlan-model",
      buildInfoPackage := "jorlan",
      commonSettings,
      buildInfoKeys ++= Seq[BuildInfoKey](
        BuildInfoKey.action("buildTime") {
          System.currentTimeMillis
        },
      ),
      libraryDependencies ++= Seq(
        "net.leibman" % "zio-auth_3" % zioAuth withSources (), // I don't know why %% isn't working.
      ),
    )
    .jvmSettings(
      scalacOptions ++= scala3Opts :+ "-Werror",
      // Fork so scoverage 2.x measurement files are flushed when the test JVM exits.
      Test / fork := true,
      // Exclude macro-only packages; their code runs at compile time and is never instrumented.
      coverageExcludedPackages := "zio\\.json\\.literal.*",
      libraryDependencies ++= Seq(
        "dev.zio"     %% "zio"                 % zioVersion withSources (),
        "dev.zio"     %% "zio-nio"             % zioNioVersion withSources (),
        "dev.zio"     %% "zio-config-magnolia" % zioConfigVersion withSources (),
        "dev.zio"     %% "zio-config-typesafe" % zioConfigVersion withSources (),
        "dev.zio"     %% "zio-json"            % zioJsonVersion withSources (),
        "dev.zio"     %% "zio-prelude"         % zioPreludeVersion withSources (),
        "dev.zio"     %% "zio-http"            % zioHttpVersion withSources (),
        "io.kevinlee" %% "just-semver-core"    % justSemverCoreVersion withSources (),
        "dev.zio"     %% "zio-test"            % zioVersion % Test withSources (),
        "dev.zio"     %% "zio-test-sbt"        % zioVersion % Test withSources (),
      ),
      Test / testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
    )
    .jsSettings(
      coverageEnabled := false,
      scalacOptions ++= scala3Opts,
      libraryDependencies ++= Seq(
        "net.leibman"               % "zio-auth_sjs1_3" % zioAuth withSources (), // I don't know why %% isn't working.
        "dev.zio" %%% "zio"         % zioVersion withSources (),
        "dev.zio" %%% "zio-json"    % zioJsonVersion withSources (),
        "dev.zio" %%% "zio-prelude" % zioPreludeVersion withSources (),
        "io.kevinlee" %%% "just-semver-core"                                % justSemverCoreVersion withSources (),
        "com.github.plokhotnyuk.jsoniter-scala" %%% "jsoniter-scala-core"   % jsoniterVersion,
        "com.github.plokhotnyuk.jsoniter-scala" %%% "jsoniter-scala-macros" % jsoniterVersion,
      ),
    )

/////////////////////////////////////////////////////////////////////////////////////
// client gql code
lazy val gqlClient =
  crossProject(JSPlatform, JVMPlatform)
    .in(file("gql-client"))
    .dependsOn(model)
    .jsSettings(
      coverageEnabled := false,
      libraryDependencies ++= Seq(
        "com.github.japgolly.scalajs-react" %%% "core"  % scalajsReactVersion withSources (),
        "com.github.japgolly.scalajs-react" %%% "extra" % scalajsReactVersion withSources (),
      ),
    )
    .settings(
      scalacOptions ++= scala3Opts :+ "-Werror",
      name := "jorlan-connector-api",
      libraryDependencies ++= Seq(
        "dev.zio"               %% "zio"            % zioVersion withSources (),
        "dev.zio"               %% "zio-json"       % zioJsonVersion withSources (),
        "com.github.ghostdogpr" %% "caliban-client" % calibanClientVersion withSources (),
        // Testing
        "dev.zio" %% "zio-test"     % zioVersion % "test" withSources (),
        "dev.zio" %% "zio-test-sbt" % zioVersion % "test" withSources (),
      ),
    )

////////////////////////////////////////////////////////////////////////////////////
// Connector API — plugin trait seam (Skill, ConnectorSkill, MessageIngress, InboundMessage, ...)

lazy val skillApi =
  crossProject(JSPlatform, JVMPlatform)
    .in(file("skill-api"))
    .enablePlugins(AutomateHeaderPlugin)
    .settings(commonSettings)
    .dependsOn(model)
    .jsSettings(
      coverageEnabled := false,
      libraryDependencies ++= Seq(
        "io.github.cquiroz" %%% "scala-java-time"       % scalaJavaTimeVersion withSources (),
        "io.github.cquiroz" %%% "scala-java-time-tzdb"  % scalaJavaTimeVersion withSources (),
        "org.scala-js" %%% "scalajs-dom"                % scalajsDomVersion withSources (),
        "com.olvind" %%% "scalablytyped-runtime"        % scalablytypedRuntimeVersion,
        "com.github.japgolly.scalajs-react" %%% "core"  % scalajsReactVersion withSources (),
        "com.github.japgolly.scalajs-react" %%% "extra" % scalajsReactVersion withSources (),
        "com.lihaoyi" %%% "scalatags"                   % scalatagsVersion withSources (),
        "com.github.japgolly.scalacss" %%% "core"       % scalacssVersion withSources (),
        "com.github.japgolly.scalacss" %%% "ext-react"  % scalacssVersion withSources (),
      ),
    )
    .settings(
      scalacOptions ++= scala3Opts :+ "-Werror",
      name := "jorlan-connector-api",
      libraryDependencies ++= Seq(
        "dev.zio" %% "zio"      % zioVersion withSources (),
        "dev.zio" %% "zio-json" % zioJsonVersion withSources (),
        // Testing
        "dev.zio" %% "zio-test"     % zioVersion % "test" withSources (),
        "dev.zio" %% "zio-test-sbt" % zioVersion % "test" withSources (),
      ),
      Test / testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
    )

/*Common stuff that all skill modules should have, at least the ones provided from "the factory" */
lazy val skillModule: CrossProject => CrossProject =
  _.enablePlugins(AutomateHeaderPlugin, BuildInfoPlugin)
    .dependsOn(model, skillApi)
    .jsEnablePlugins(ScalaJSPlugin)
    .jsSettings(
      coverageEnabled := false,
      scalaJSLinkerConfig ~= {
        _.withModuleKind(ModuleKind.NoModule)
          .withOutputPatterns(org.scalajs.linker.interface.OutputPatterns.fromJSFile("%s-skill.js"))
      },
      Compile / scalaJSMainModuleInitializer := Def.task {
        (Compile / scalaJSMainModuleInitializer).value.map { initializer =>
          initializer.withModuleID(name.value)
        }
      }.value,
      scalaJSUseMainModuleInitializer := true,
      // Compile skill JS and copy to the global debugDist/skills or dist/skills directories.
      // Each skill's JS file is served at /skills/<name>-skill.js.
      debugDist := {
        val _ = (Compile / fastLinkJS).value
        val srcDir = (Compile / fastLinkJS / scalaJSLinkerOutputDirectory).value
        val outDir = (ThisBuild / baseDirectory).value / "debugDist" / "skills"
        IO.createDirectory(outDir)
        (srcDir * GlobFilter("*-skill.js")).get().foreach(f => IO.copyFile(f, outDir / f.name))
        (srcDir * GlobFilter("*-skill.map")).get().foreach(f => IO.copyFile(f, outDir / f.name))
        outDir
      },
      dist := {
        val _ = (Compile / fullLinkJS).value
        val srcDir = (Compile / fullLinkJS / scalaJSLinkerOutputDirectory).value
        val outDir = (ThisBuild / baseDirectory).value / "dist" / "skills"
        IO.createDirectory(outDir)
        (srcDir * GlobFilter("*-skill.js")).get().foreach(f => IO.copyFile(f, outDir / f.name))
        (srcDir * GlobFilter("*-skill.map")).get().foreach(f => IO.copyFile(f, outDir / f.name))
        outDir
      },
    )
    .settings(
      commonSettings,
      buildInfoPackage := "jorlan.skill",
      scalacOptions ++= scala3Opts :+ "-Werror",
      libraryDependencies ++= Seq(
        "dev.zio" %% "zio"      % zioVersion withSources (),
        "dev.zio" %% "zio-json" % zioJsonVersion withSources (),
        "dev.zio" %% "zio-http" % zioHttpVersion withSources (),
        // Testing
        "dev.zio" %% "zio-test"     % zioVersion % "test" withSources (),
        "dev.zio" %% "zio-test-sbt" % zioVersion % "test" withSources (),
      ),
      Test / testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
      Test / fork := false,
    )
    .jvmSettings(
      // Fork JVM tests so scoverage 2.x measurement files are flushed on JVM exit.
      Test / fork := true,
    )

////////////////////////////////////////////////////////////////////////////////////
// Telegram Connector — TelegramConnectorSkill + TelegramApiClient

lazy val telegramConnector =
  crossProject(JSPlatform, JVMPlatform)
    .in(file("telegram"))
    .configureCross(skillModule)
    .settings(name := "jorlan-telegram")
    .jvmSettings(
      libraryDependencies ++= Seq(
        "io.github.apimorphism" %% "telegramium-core" % telegramiumVersion withSources (),
      ),
    )

////////////////////////////////////////////////////////////////////////////////////
// Calculator Skill — mXparser-based math expression evaluator

lazy val calculatorSkill =
  crossProject(JSPlatform, JVMPlatform)
    .in(file("calculator"))
    .configureCross(skillModule)
    .settings(name := "jorlan-calculator")
    .jvmSettings(
      libraryDependencies ++= Seq(
        "org.mariuszgromada.math" % "MathParser.org-mXparser" % "6.1.1" withSources (),
      ),
    )

////////////////////////////////////////////////////////////////////////////////////
// Lyrion Music Skill — JSON-RPC client for Lyrion Music Server (Squeezebox)

lazy val lyrionSkill =
  crossProject(JSPlatform, JVMPlatform)
    .in(file("lyrion"))
    .configureCross(skillModule)
    .settings(name := "jorlan-lyrion")

////////////////////////////////////////////////////////////////////////////////////
// Email Connector — IMAP/SMTP provider + PGP service

lazy val emailConnector =
  crossProject(JSPlatform, JVMPlatform)
    .in(file("email"))
    .configureCross(skillModule)
    .settings(name := "jorlan-email")
    .jvmSettings(
      // Email provider code requires real IMAP/SMTP/PGP infrastructure; no unit-testable code paths.
      coverageExcludedPackages := "jorlan\\.email.*",
      libraryDependencies ++= Seq(
        "com.github.eikek" %% "emil-common"      % emilVersion withSources (),
        "com.github.eikek" %% "emil-javamail"    % emilVersion withSources (),
        "dev.zio"          %% "zio-interop-cats" % zioInteropCatsVersion withSources (),
        "org.bouncycastle"  % "bcpg-jdk18on"     % bouncyCastleVersion withSources (),
      ),
    )

////////////////////////////////////////////////////////////////////////////////////
// Unit Conversion Skill — squants-backed unit conversion

lazy val unitConversionSkill =
  crossProject(JSPlatform, JVMPlatform)
    .in(file("unit-conversion"))
    .configureCross(skillModule)
    .settings(name := "jorlan-unit-conversion")
    .jvmSettings(
      libraryDependencies ++= Seq(
        "org.typelevel" %% "squants" % "1.8.3" withSources (),
      ),
    )

////////////////////////////////////////////////////////////////////////////////////
// HTTP Fetch Skill — capability-gated HTTP GET/POST for agents

lazy val httpFetchSkill =
  crossProject(JSPlatform, JVMPlatform)
    .in(file("http-fetch"))
    .configureCross(skillModule)
    .settings(name := "jorlan-http-fetch")

////////////////////////////////////////////////////////////////////////////////////
// Weather Skill — OpenWeatherMap current conditions, forecast, and alerts

lazy val weatherSkill =
  crossProject(JSPlatform, JVMPlatform)
    .in(file("weather"))
    .configureCross(skillModule)
    .settings(name := "jorlan-weather")

////////////////////////////////////////////////////////////////////////////////////
// Time Skill — java.time-based timezone/datetime skill (no external dependencies)

lazy val timeSkill =
  crossProject(JSPlatform, JVMPlatform)
    .in(file("time-skill"))
    .configureCross(skillModule)
    .settings(name := "jorlan-time")

////////////////////////////////////////////////////////////////////////////////////
// Market Data — Alpha Vantage market data skill

lazy val marketDataSkill =
  crossProject(JSPlatform, JVMPlatform)
    .in(file("market-data"))
    .configureCross(skillModule)
    .settings(name := "jorlan-market-data")

////////////////////////////////////////////////////////////////////////////////////
// Search Skill — Tavily web search API

lazy val searchSkill =
  crossProject(JSPlatform, JVMPlatform)
    .in(file("search"))
    .configureCross(skillModule)
    .settings(name := "jorlan-search")

////////////////////////////////////////////////////////////////////////////////////
// Google Services — Gmail/Calendar/Drive REST API providers + OAuth credential service

lazy val googleServices =
  crossProject(JSPlatform, JVMPlatform)
    .in(file("google-services"))
    .configureCross(skillModule)
    .settings(name := "jorlan-google-services")
    .jvmSettings(
      // Exclude actual Google API provider implementations — they make live Google SDK calls
      // and are not testable in unit tests without real OAuth credentials.
      coverageExcludedFiles :=
        ".*GoogleCalendarProvider.*;.*GmailProvider.*;.*GoogleDriveProvider.*;.*GoogleContactsProvider.*;.*GoogleApiProvider.*",
      libraryDependencies ++= Seq(
        "com.google.api-client" % "google-api-client"               % googleApiClientVersion withSources (),
        "com.google.apis"       % "google-api-services-gmail"       % googleApisGmailVersion withSources (),
        "com.google.apis"       % "google-api-services-calendar"    % googleApisCalendarVersion withSources (),
        "com.google.apis"       % "google-api-services-drive"       % googleApisDriveVersion withSources (),
        "com.google.apis"       % "google-api-services-people"      % googleApisPeopleVersion withSources (),
        "com.google.auth"       % "google-auth-library-oauth2-http" % googleAuthLibraryVersion withSources (),
      ),
    )

//////////////////////////////////////////////////////////////////////////////////////////////////
// Server
lazy val server = project
  .enablePlugins(
    AutomateHeaderPlugin,
    com.github.sbt.git.GitVersioning,
    LinuxPlugin,
    DebianPlugin,
    DebianDeployPlugin,
    JavaServerAppPackaging,
    SystemloaderPlugin,
    SystemdPlugin,
    CalibanPlugin,
  )
  .settings(debianSettings, commonSettings)
  .dependsOn(
    modelJVM,
    ai,
    skillApi.jvm,
    calculatorSkill.jvm,
    telegramConnector.jvm,
    emailConnector.jvm,
    googleServices.jvm,
    unitConversionSkill.jvm,
    lyrionSkill.jvm,
    marketDataSkill.jvm,
    weatherSkill.jvm,
    timeSkill.jvm,
    searchSkill.jvm,
    httpFetchSkill.jvm,
  )
  .settings(
    scalacOptions ++= scala3Opts :+ "-Werror",
    name := "jorlan-server",
    libraryDependencies ++= Seq(
      // DB
      "org.mariadb.jdbc" % "mariadb-java-client" % mariadbVersion withSources (),
      "io.getquill"     %% "quill-jdbc-zio"      % quillVersion withSources (),
      "org.flywaydb"     % "flyway-core"         % flywayVersion withSources (),
      "org.flywaydb"     % "flyway-mysql"        % flywayVersion withSources (),
      // Log
      "ch.qos.logback" % "logback-classic" % logbackVersion withSources (),
      // ZIO
      "dev.zio"                       %% "zio"                   % zioVersion withSources (),
      "dev.zio"                       %% "zio-nio"               % zioNioVersion withSources (),
      "dev.zio"                       %% "zio-cache"             % zioCacheVersion withSources (),
      "dev.zio"                       %% "zio-config"            % zioConfigVersion withSources (),
      "dev.zio"                       %% "zio-config-derivation" % zioConfigVersion withSources (),
      "dev.zio"                       %% "zio-config-magnolia"   % zioConfigVersion withSources (),
      "dev.zio"                       %% "zio-config-typesafe"   % zioConfigVersion withSources (),
      "dev.zio"                       %% "zio-logging-slf4j2"    % zioLoggingSlf4j2Version withSources (),
      "dev.zio"                       %% "izumi-reflect"         % izumiReflectVersion withSources (),
      "com.github.ghostdogpr"         %% "caliban"               % calibanVersion withSources (),
      "com.github.ghostdogpr"         %% "caliban-quick"         % calibanVersion withSources (),
      "dev.zio"                       %% "zio-http"              % zioHttpVersion withSources (),
      "com.github.jwt-scala"          %% "jwt-circe"             % jwtCirceVersion withSources (),
      "com.github.jwt-scala"          %% "jwt-zio-json"          % jwtZioJsonVersion withSources (),
      "dev.zio"                       %% "zio-json"              % zioJsonVersion withSources (),
      "dev.zio"                       %% "zio-process"           % zioProcessVersion withSources (),
      "com.softwaremill.sttp.client4" %% "core"                  % sttpClient4Version withSources (),
      "com.softwaremill.sttp.client4" %% "zio"                   % sttpClient4Version withSources (),
      "com.softwaremill.sttp.client4" %% "zio-json"              % sttpClient4Version withSources (),
      "com.github.alonsodomin.cron4s" %% "cron4s-core"           % cron4sVersion withSources (),
      // Other random utilities
      "com.github.daddykotex" %% "courier" % courierVersion withSources (),
      // Testing
      "com.dimafeng" %% "testcontainers-scala-mariadb" % testContainerVersion % "test" withSources (),
      "dev.zio"      %% "zio-test"                     % zioVersion           % "test" withSources (),
      "dev.zio"      %% "zio-test-sbt"                 % zioVersion           % "test" withSources (),
    ),
    Test / testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
    // Fork so the JVM shutdown hook flushes Scala 3 coverage measurements to disk.
    Test / fork           := true,
    coverageExcludedFiles := ".*EnvironmentBuilder.*;.*scala/jorlan/Jorlan.*",
    // Skip Scaladoc during packaging — cron4s has a Scala.js annotation that breaks DottyDoc on JVM.
    Compile / doc / sources := Seq.empty,
  )

//////////////////////////////////////////////////////////////////////////////////////////////////
// Integration Tests
lazy val integration = project
  .enablePlugins(
    AutomateHeaderPlugin,
    com.github.sbt.git.GitVersioning,
  )
  .settings(commonSettings)
  .dependsOn(modelJVM, server, shell, skillApi.jvm, telegramConnector.jvm)
  .settings(
    scalacOptions ++= scala3Opts :+ "-Werror",
    name := "jorlan-integration",
    // Pull SQL migrations from server resources so Flyway can find them in tests
    Test / unmanagedResourceDirectories += (server / Compile / resourceDirectory).value,
    libraryDependencies ++= Seq(
      // DB
      "org.mariadb.jdbc" % "mariadb-java-client"          % mariadbVersion withSources (),
      "org.flywaydb"     % "flyway-core"                  % flywayVersion withSources (),
      "org.flywaydb"     % "flyway-mysql"                 % flywayVersion withSources (),
      "com.dimafeng"    %% "testcontainers-scala-mariadb" % testContainerVersion withSources (),
      // Log
      "ch.qos.logback" % "logback-classic" % logbackVersion withSources (),
      // ZIO
      "dev.zio" %% "zio"                 % zioVersion withSources (),
      "dev.zio" %% "zio-nio"             % zioNioVersion withSources (),
      "dev.zio" %% "zio-config"          % zioConfigVersion withSources (),
      "dev.zio" %% "zio-config-magnolia" % zioConfigVersion withSources (),
      "dev.zio" %% "zio-config-typesafe" % zioConfigVersion withSources (),
      "dev.zio" %% "zio-json"            % zioJsonVersion withSources (),
      // other
      "io.github.apimorphism" %% "telegramium-core" % telegramiumVersion withSources (),
      // Testing
      "dev.zio" %% "zio-test"     % zioVersion % "test" withSources (),
      "dev.zio" %% "zio-test-sbt" % zioVersion % "test" withSources (),
    ),
    // Integration tests are only in the Test configuration
    Test / testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
    Test / parallelExecution := false,
    // Fork so Testcontainers' non-daemon threads (HikariCP, Docker client) don't
    // keep SBT's JVM alive after tests complete.
    Test / fork := true,
  )

////////////////////////////////////////////////////////////////////////////////////
// Shell — CLI client (connects to server via GraphQL)

lazy val shell = project
  .dependsOn(gqlClient.jvm)
  .enablePlugins(
    AutomateHeaderPlugin,
    com.github.sbt.git.GitVersioning,
    JavaAppPackaging,
    LinuxPlugin,
    DebianPlugin,
  )
  .settings(shellDebianSettings, commonSettings)
  .settings(
    scalacOptions ++= scala3Opts :+ "-Werror",
    name                := "jorlan-shell",
    Compile / mainClass := Some("jorlan.shell.JorlanShell"),
    libraryDependencies ++= Seq(
      "dev.zio"                       %% "zio"                 % zioVersion withSources (),
      "dev.zio"                       %% "zio-config"          % zioConfigVersion withSources (),
      "dev.zio"                       %% "zio-config-magnolia" % zioConfigVersion withSources (),
      "dev.zio"                       %% "zio-config-typesafe" % zioConfigVersion withSources (),
      "dev.zio"                       %% "zio-json"            % zioJsonVersion withSources (),
      "dev.zio"                       %% "zio-logging-slf4j2"  % zioLoggingSlf4j2Version withSources (),
      "com.github.ghostdogpr"         %% "caliban-client"      % calibanClientVersion withSources (),
      "com.softwaremill.sttp.client4" %% "core"                % sttpClient4Version withSources (),
      "com.softwaremill.sttp.client4" %% "zio"                 % sttpClient4Version withSources (),
      "com.softwaremill.sttp.client4" %% "zio-json"            % sttpClient4Version withSources (),
      "com.googlecode.lanterna"        % "lanterna"            % lanternaVersion withSources (),
      "ch.qos.logback"                 % "logback-classic"     % logbackVersion withSources (),
      // Testing
      "dev.zio" %% "zio-test"     % zioVersion % "test" withSources (),
      "dev.zio" %% "zio-test-sbt" % zioVersion % "test" withSources (),
    ),
    Test / testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
    // Fork so the shell process owns the TTY; connectInput passes stdin through
    // so Lanterna can put the terminal into raw mode and receive keystrokes.
    fork                             := true,
    run / fork                       := true,
    run / connectInput               := true,
    coverageExcludedFiles            := ".*JorlanClient.*;.*JorlanScreen.*;.*JorlanShell.*",
    coverageExcludedPackages         := "jorlan\\.shell\\.tui.*",
    assembly / mainClass             := Some("jorlan.shell.JorlanShell"),
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", xs @ _*) => MergeStrategy.discard
      case x                             => MergeStrategy.preferProject
    },
  )
  .dependsOn(modelJVM)

//////////////////////////////////////////////////////////////////////////////////////////////////
// Utility
lazy val debianSettings =
  Seq(
    Compile / mainClass := Some("jorlan.Jorlan"),
    Debian / name       := "jorlan-server",
    // Debian versions must start with a digit; sbt-git uses git hash in dev
    Debian / version := {
      val v = version.value
      if (v.headOption.exists(_.isDigit)) v else "0.1.0-SNAPSHOT"
    },
    Debian / packageDescription := "Jorlan Secure Agent Runtime",
    Debian / packageSummary     := "Jorlan Secure Agent Runtime",
    Debian / debianChangelog    := Some(file("debian/changelog")),
    Debian / debianPackageDependencies += "default-jre-headless (>= 2:1.21)",
    Linux / maintainer                  := "Roberto Leibman <roberto@leibman.net>",
    Linux / daemonUser                  := "jorlan",
    Linux / daemonGroup                 := "jorlan",
    Linux / defaultLinuxInstallLocation := "/usr/lib",
    Debian / serverLoading              := Some(ServerLoader.Systemd),
    // JVM flags picked up by the generated launch script
    Universal / javaOptions ++= Seq(
      "-Dlogback.configurationFile=/etc/jorlan-server/logback.xml",
      s"-Dconfig.file=/etc/jorlan-server/application.conf",
      "-Djava.net.preferIPv4Stack=true",
    ),
    // Map templates into the universal (tarball) layout
    Universal / mappings += {
      val src = sourceDirectory.value
      (src / "templates" / "application.conf") -> "conf/application.conf"
    },
    Universal / mappings += {
      val src = sourceDirectory.value
      (src / "templates" / "logback.xml") -> "conf/logback.xml"
    },
    Universal / mappings += {
      val src = sourceDirectory.value
      (src / "templates" / "server.env") -> "conf/server.env"
    },
    Universal / mappings += {
      val src = sourceDirectory.value
      (src / "templates" / "io.jorlan.server.plist") -> "launchd/io.jorlan.server.plist"
    },
    Universal / mappings += {
      val src = sourceDirectory.value
      (src / "main" / "scripts" / "init-db.sh") -> "scripts/init-db.sh"
    },
    Universal / mappings += {
      val src = sourceDirectory.value
      (src / "main" / "scripts" / "install-macos.sh") -> "scripts/install-macos.sh"
    },
    // Install config files to /etc/jorlan-server/ — marked .withConfig() so dpkg does not
    // overwrite them on upgrade if the admin has modified them.
    Debian / linuxPackageMappings += {
      val src = sourceDirectory.value
      packageMapping(
        (src / "templates" / "application.conf") -> "/etc/jorlan-server/application.conf",
        (src / "templates" / "logback.xml")      -> "/etc/jorlan-server/logback.xml",
      ).withUser("jorlan").withGroup("jorlan").withPerms("0644").withConfig()
    },
    // Install env template to /etc/jorlan/server.env — also .withConfig()
    Debian / linuxPackageMappings += {
      val src = sourceDirectory.value
      packageMapping(
        (src / "templates" / "server.env") -> "/etc/jorlan/server.env",
      ).withUser("jorlan").withGroup("jorlan").withPerms("0600").withConfig()
    },
    // Install init-db.sh so admins can run it directly
    Debian / linuxPackageMappings += {
      val src = sourceDirectory.value
      packageMapping(
        (src / "main" / "scripts" / "init-db.sh") -> "/usr/lib/jorlan-server/scripts/init-db.sh",
      ).withUser("root").withGroup("root").withPerms("0755")
    },
    // Install web frontend assets so the server can serve them directly.
    // Depends on web/dist so packaging always uses a fresh build.
    Debian / linuxPackageMappings += Def.task {
      val distDir = (web / dist).value
      packageMapping(
        (distDir.allPaths --- distDir).get.map { f =>
          f -> s"/usr/lib/jorlan-server/www/${Path.relativeTo(distDir)(f).get}"
        }: _*,
      ).withUser("jorlan").withGroup("jorlan")
    }.value,
    // Include web frontend in the universal (macOS) tarball under www/
    Universal / mappings ++= Def.task {
      val distDir = (web / dist).value
      (distDir.allPaths --- distDir).get.map { f =>
        f -> s"www/${Path.relativeTo(distDir)(f).get}"
      }
    }.value,
    // postinst: create log directory and set permissions
    Debian / maintainerScripts := {
      val scripts:  Map[String, Seq[String]] = (Debian / maintainerScripts).value
      val postinst: Seq[String] = scripts.getOrElse("postinst", Seq.empty)
      val logSetup = Seq(
        "mkdir -p /var/log/jorlan-server/conversations",
        "chown -R jorlan:jorlan /var/log/jorlan-server",
        "chmod 750 /var/log/jorlan-server",
        "mkdir -p /etc/jorlan",
        "chown root:jorlan /etc/jorlan",
        "chmod 750 /etc/jorlan",
      )
      val prerm: Seq[String] = scripts.getOrElse("prerm", Seq.empty)
      val stopService = Seq(
        "if command -v systemctl > /dev/null && systemctl is-active --quiet jorlan-server; then",
        "  systemctl stop jorlan-server || true",
        "fi",
      )
      scripts + ("postinst" -> (logSetup ++ postinst)) + ("prerm" -> (stopService ++ prerm))
    },
  )

lazy val shellDebianSettings =
  Seq(
    Debian / name    := "jorlan-shell",
    Debian / version := {
      val v = version.value
      if (v.headOption.exists(_.isDigit)) v else "0.1.0-SNAPSHOT"
    },
    Debian / packageDescription := "Jorlan Shell — CLI client for the Jorlan server",
    Debian / packageSummary     := "Jorlan Shell CLI client",
    Debian / debianChangelog    := Some(file("debian/changelog")),
    Debian / debianPackageDependencies += "default-jre-headless (>= 2:1.21)",
    Linux / maintainer                  := "Roberto Leibman <roberto@leibman.net>",
    Linux / defaultLinuxInstallLocation := "/usr/lib",
    executableScriptName                := "jorlan",
  )

////////////////////////////////////////////////////////////////////////////////////
// AI — LangChain4j wrapped in ZIO (Ollama + cloud model providers)

lazy val ai = project
  .enablePlugins(
    AutomateHeaderPlugin,
    com.github.sbt.git.GitVersioning,
  )
  .settings(
    scalacOptions ++= scala3Opts :+ "-Werror",
    name := "jorlan-ai",
    commonSettings,
    libraryDependencies ++= Seq(
      "dev.zio"           %% "zio"                  % zioVersion withSources (),
      "dev.zio"           %% "zio-json"             % zioJsonVersion withSources (),
      "org.testcontainers" % "qdrant"               % qdrantVersion withSources (),
      "dev.langchain4j"    % "langchain4j-core"     % langchainCoreVersion withSources (),
      "dev.langchain4j"    % "langchain4j"          % langchainCoreVersion withSources (),
      "dev.langchain4j"    % "langchain4j-ollama"   % langchain4jOllamaVersion withSources (),
      "dev.langchain4j"    % "langchain4j-easy-rag" % langchainLibrariesVersion withSources (),
      "dev.langchain4j"    % "langchain4j-qdrant"   % langchainLibrariesVersion withSources (),
      "dev.langchain4j"    % "langchain4j-mariadb"  % langchainLibrariesVersion withSources (),
      // Testing
      "dev.zio" %% "zio-test"     % zioVersion % "test" withSources (),
      "dev.zio" %% "zio-test-sbt" % zioVersion % "test" withSources (),
    ),
    Test / testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
    // Fork so the JVM shutdown hook flushes Scala 3 coverage measurements to disk.
    Test / fork := true,
  )

////////////////////////////////////////////////////////////////////////////////////
// Web

lazy val commonWeb: Project => Project =
  _.settings(
    libraryDependencies ++= Seq(
      "net.leibman" %%% "jorlan-stlib" % stlibVersion withSources (),
      "net.leibman" % "zio-auth_sjs1_3" % zioAuth withSources (), // I don't know why %%% isn't working.
      "com.github.ghostdogpr" %%% "caliban-client"    % calibanClientVersion withSources (),
      "dev.zio" %%% "zio"                             % zioVersion withSources (),
      "com.softwaremill.sttp.client4" %%% "core"      % sttpClient4Version withSources (),
      "com.softwaremill.sttp.client4" %%% "zio-json"  % sttpClient4Version withSources (),
      "io.github.cquiroz" %%% "scala-java-time"       % scalaJavaTimeVersion withSources (),
      "io.github.cquiroz" %%% "scala-java-time-tzdb"  % scalaJavaTimeVersion withSources (),
      "org.scala-js" %%% "scalajs-dom"                % scalajsDomVersion withSources (),
      "com.olvind" %%% "scalablytyped-runtime"        % scalablytypedRuntimeVersion,
      "com.github.japgolly.scalajs-react" %%% "core"  % scalajsReactVersion withSources (),
      "com.github.japgolly.scalajs-react" %%% "extra" % scalajsReactVersion withSources (),
      "com.lihaoyi" %%% "scalatags"                   % scalatagsVersion withSources (),
      "com.github.japgolly.scalacss" %%% "core"       % scalacssVersion withSources (),
      "com.github.japgolly.scalacss" %%% "ext-react"  % scalacssVersion withSources (),
      // Testing
      "dev.zio" %%% "zio-test"     % zioVersion % "test" withSources (),
      "dev.zio" %%% "zio-test-sbt" % zioVersion % "test" withSources (),
    ),
    dependencyOverrides ++= Seq(
      "com.github.japgolly.scalajs-react" %%% "core"  % scalajsReactVersion,
      "com.github.japgolly.scalajs-react" %%% "extra" % scalajsReactVersion,
    ),
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
    organizationName                     := "Roberto Leibman",
    startYear                            := Some(2026),
    Compile / unmanagedSourceDirectories := Seq((Compile / scalaSource).value),
    Test / unmanagedSourceDirectories    := Seq((Test / scalaSource).value),
  )

lazy val web: Project = project
  .dependsOn(modelJS, gqlClient.js)
  .configure(commonWeb)
  .settings(commonSettings)
  .enablePlugins(
    // AutomateHeaderPlugin,
    com.github.sbt.git.GitVersioning,
    ScalaJSPlugin,
    ScalaJSEsbuildWebPlugin,
  )
  .settings(
    scalacOptions ++= scala3Opts,
    name := "jorlan-web",
    // The entire web module compiles to JavaScript (Scala.js) and requires a
    // browser runtime — there are no JVM-runnable tests. Disable scoverage so it
    // doesn't instrument Scala.js bytecode or report 0% coverage.
    coverageEnabled := false,
    run / fork                                := true,
    Global / scalaJSStage                     := FastOptStage,
    Compile / scalaJSUseMainModuleInitializer := true,
    Test / scalaJSUseMainModuleInitializer    := false,
    libraryDependencies ++= Seq(
      "dev.zio" %%% "zio"      % zioVersion withSources (),
      "dev.zio" %%% "zio-json" % zioJsonVersion withSources (),
    ),
    debugDist := {
      val _ = (Compile / fastLinkJS / esbuildBundle).value
      webDistImpl(
        assets = (ThisBuild / baseDirectory).value / "web" / "src" / "main" / "web",
        bundleOutput = (Compile / esbuildBundle / crossTarget).value,
        outputFolder = (ThisBuild / baseDirectory).value / "debugDist",
      )
    },
    dist := {
      val _ = (Compile / fullLinkJS / esbuildBundle).value
      webDistImpl(
        assets = (ThisBuild / baseDirectory).value / "web" / "src" / "main" / "web",
        bundleOutput = (Compile / esbuildBundle / crossTarget).value,
        outputFolder = (ThisBuild / baseDirectory).value / "dist",
      )
    },
  )

//////////////////////////////////////////////////////////////////////////////////////////////////
// Root project
lazy val root = project
  .in(file("."))
  .aggregate(
    modelJVM,
    modelJS,

    skillApi.jvm,
    calculatorSkill.jvm,
    telegramConnector.jvm,
    emailConnector.jvm,
    googleServices.jvm,
    marketDataSkill.jvm,
    weatherSkill.jvm,
    searchSkill.jvm,
    lyrionSkill.jvm,
    unitConversionSkill.jvm,
    timeSkill.jvm,
    httpFetchSkill.jvm,

    skillApi.js,
    calculatorSkill.js,
    telegramConnector.js,
    emailConnector.js,
    googleServices.js,
    marketDataSkill.js,
    weatherSkill.js,
    searchSkill.js,
    lyrionSkill.js,
    unitConversionSkill.js,
    timeSkill.js,
    httpFetchSkill.js,

    ai,
    server,
    shell,
    integration,
    web,
  )
  .settings(
    name           := "jorlan",
    publish / skip := true,
    version        := "0.1.0",
    startYear        := Some(2026),
    licenses += ("Apache-2.0", url("https://www.apache.org/licenses/LICENSE-2.0.txt")),
    headerLicense := Some(HeaderLicense.ALv2("2026", "Roberto Leibman", HeaderLicenseStyle.SpdxSyntax))
  )
