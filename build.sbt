////////////////////////////////////////////////////////////////////////////////////
// Common Stuff

import org.apache.commons.io.FileUtils

import java.nio.file.Files
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
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
  assets:            File,
  webpackArtifacts:  Seq[Attributed[File]],
  artifactFolder:    File,
  outputFolder:      File,
  includeSourceMaps: Boolean,
): File = {
  outputFolder.mkdirs()
  FileUtils.copyDirectory(assets, outputFolder, true)
  if (artifactFolder.exists()) {
    println(s"Copying webpack output from: $artifactFolder")
    val bundles = (artifactFolder * "*.js").get ++
      (if (includeSourceMaps) (artifactFolder * "*.js.map").get else Seq.empty)
    bundles.foreach { bundleFile =>
      Files.copy(bundleFile.toPath, (outputFolder / bundleFile.name).toPath, REPLACE_EXISTING)
    }
  } else {
    println(s"Webpack output directory does not exist: $artifactFolder")
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

val telegramiumVersion = "10.1000.0"
val calibanClientVersion = "3.1.2"
val calibanVersion = "3.1.2"
val commonsCodecVersion = "1.21.0"
val courierVersion = "4.0.0-RC1"
val cron4sVersion = "0.8.2"
val dispatchHttpVersion = "2.0.0"
val flywayVersion = "12.8.1"
val izumiReflectVersion = "3.0.9"
val jaxbApiVersion = "2.3.1"
val jsoniterVersion = "2.38.9"
val justSemverCoreVersion = "1.3.0"
val jwtCirceVersion = "11.0.4"
val jwtZioJsonVersion = "11.0.4"
val langchain4jOllamaVersion = "1.16.2"
val langchainCoreVersion = "1.16.2"
val langchainLibrariesVersion = "1.16.2-beta26"
val lanternaVersion = "3.1.5"
val logbackVersion = "1.5.34"
val mariadbVersion = "3.5.8"
val openPdfVersion = "3.0.3"
val qdrantVersion = "1.21.4"
val quillVersion = "4.8.6"
val scalablytypedRuntimeVersion = "2.4.2"
val scalacssVersion = "1.0.0"
val scalaJavaTimeVersion = "2.6.0"
val scalajsDomVersion = "2.8.1"
val scalajsReactVersion = "4.0.0"
val scalatagsVersion = "0.13.1"
val stlibVersion = "1.0.0"
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
  startYear        := Some(2024),
  organizationName := "Roberto Leibman",
  headerLicense    := Some(
    HeaderLicense.Custom(
      """Copyright (c) 2026 Roberto Leibman - All Rights Reserved
        |
        |This source code is protected under international copyright law.  All rights
        |reserved and protected by the copyright holders.
        |This file is confidential and only available to authorized individuals with the
        |permission of the copyright holders.  If you encounter this file and do not have
        |permission, please contact the copyright holders and delete this file.""".stripMargin,
    ),
  ),
  resolvers += Resolver.mavenLocal,
)

////////////////////////////////////////////////////////////////////////////////////
// Model
lazy val model = project
  .enablePlugins(
    AutomateHeaderPlugin,
    com.github.sbt.git.GitVersioning,
    BuildInfoPlugin,
  )
  .settings(
    scalacOptions ++= scala3Opts :+ "-Werror",
    name             := "jorlan-model",
    buildInfoPackage := "jorlan",
    buildInfoKeys ++= Seq[BuildInfoKey](
      BuildInfoKey.action("buildTime") {
        System.currentTimeMillis
      },
    ),
    commonSettings,
    libraryDependencies ++= Seq(
      "net.leibman" % "zio-auth_3" % zioAuth withSources (), // I don't know why %% isn't working.
    ),
    libraryDependencies ++= Seq(
      "dev.zio"     %% "zio"                 % zioVersion withSources (),
      "dev.zio"     %% "zio-nio"             % zioNioVersion withSources (),
      "dev.zio"     %% "zio-config-magnolia" % zioConfigVersion withSources (),
      "dev.zio"     %% "zio-config-typesafe" % zioConfigVersion withSources (),
      "dev.zio"     %% "zio-json"            % zioJsonVersion withSources (),
      "dev.zio"     %% "zio-prelude"         % zioPreludeVersion withSources (),
      "dev.zio"     %% "zio-http"            % zioHttpVersion withSources (),
      "io.kevinlee" %% "just-semver-core"    % justSemverCoreVersion withSources (),
    ),
  )

////////////////////////////////////////////////////////////////////////////////////
// Connector API — plugin trait seam (Skill, ConnectorSkill, MessageIngress, InboundMessage, ...)

lazy val connectorApi = project
  .in(file("connector-api"))
  .enablePlugins(AutomateHeaderPlugin)
  .settings(commonSettings)
  .dependsOn(model)
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

////////////////////////////////////////////////////////////////////////////////////
// Telegram Connector — TelegramConnectorSkill + TelegramApiClient

lazy val telegramConnector = project
  .in(file("telegram"))
  .enablePlugins(AutomateHeaderPlugin)
  .settings(commonSettings)
  .dependsOn(model, connectorApi)
  .settings(
    scalacOptions ++= scala3Opts :+ "-Werror",
    name := "jorlan-telegram",
    libraryDependencies ++= Seq(
      "dev.zio"                 %% "zio"               % zioVersion withSources (),
      "dev.zio"                 %% "zio-json"          % zioJsonVersion withSources (),
      "dev.zio"                 %% "zio-http"          % zioHttpVersion withSources (),
      "io.github.apimorphism"   %% "telegramium-core"  % telegramiumVersion withSources (),
      // Testing
      "dev.zio" %% "zio-test"     % zioVersion % "test" withSources (),
      "dev.zio" %% "zio-test-sbt" % zioVersion % "test" withSources (),
    ),
    Test / testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
    // Fork so the JVM shutdown hook flushes Scala 3 coverage measurements to disk.
    Test / fork := true,
  )

////////////////////////////////////////////////////////////////////////////////////
// Analytics

lazy val analytics = project
  .enablePlugins(
    AutomateHeaderPlugin,
    com.github.sbt.git.GitVersioning,
  )
  .settings(
    scalacOptions ++= scala3Opts :+ "-Werror",
    name := "jorlan-analytics",
    commonSettings,
    libraryDependencies ++= Seq(
      "net.leibman" % "zio-auth_3" % zioAuth withSources (), // I don't know why %% isn't working.
    ),
    libraryDependencies ++= Seq(
      "dev.zio"     %% "zio"                 % zioVersion withSources (),
      "dev.zio"     %% "zio-nio"             % zioNioVersion withSources (),
      "dev.zio"     %% "zio-config-magnolia" % zioConfigVersion withSources (),
      "dev.zio"     %% "zio-config-typesafe" % zioConfigVersion withSources (),
      "dev.zio"     %% "zio-json"            % zioJsonVersion withSources (),
      "dev.zio"     %% "zio-prelude"         % zioPreludeVersion withSources (),
      "dev.zio"     %% "zio-http"            % zioHttpVersion withSources (),
      "io.getquill" %% "quill-jdbc-zio"      % quillVersion withSources (),
      "io.kevinlee" %% "just-semver-core"    % justSemverCoreVersion withSources (),
    ),
  )
  .dependsOn(model)

//////////////////////////////////////////////////////////////////////////////////////////////////
// Server
lazy val db = project
  .enablePlugins(AutomateHeaderPlugin)
  .settings(commonSettings)
  .dependsOn(model)
  .settings(
    scalacOptions ++= scala3Opts :+ "-Werror",
    name := "jorlan-db",
    libraryDependencies ++= Seq(
      // DB
      "org.mariadb.jdbc" % "mariadb-java-client" % mariadbVersion withSources (),
      "io.getquill"     %% "quill-jdbc-zio"      % quillVersion withSources (),
      "org.flywaydb"     % "flyway-core"         % flywayVersion withSources (),
      "org.flywaydb"     % "flyway-mysql"        % flywayVersion withSources (),
      // Log
      "ch.qos.logback" % "logback-classic" % logbackVersion withSources (),
      // ZIO
      "dev.zio" %% "zio"                   % zioVersion withSources (),
      "dev.zio" %% "zio-nio"               % zioNioVersion withSources (),
      "dev.zio" %% "zio-cache"             % zioCacheVersion withSources (),
      "dev.zio" %% "zio-config"            % zioConfigVersion withSources (),
      "dev.zio" %% "zio-config-derivation" % zioConfigVersion withSources (),
      "dev.zio" %% "zio-config-magnolia"   % zioConfigVersion withSources (),
      "dev.zio" %% "zio-config-typesafe"   % zioConfigVersion withSources (),
      "dev.zio" %% "zio-logging-slf4j2"    % zioLoggingSlf4j2Version withSources (),
      "dev.zio" %% "izumi-reflect"         % izumiReflectVersion withSources (),
      "dev.zio" %% "zio-json"              % zioJsonVersion withSources (),
      // Testing
      "com.dimafeng" %% "testcontainers-scala-mariadb" % testContainerVersion % "test" withSources (),
      "dev.zio"      %% "zio-test"                     % zioVersion           % "test" withSources (),
      "dev.zio"      %% "zio-test-sbt"                 % zioVersion           % "test" withSources (),
    ),
  )

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
  .dependsOn(model, db, ai, analytics, connectorApi, telegramConnector)
  .settings(
    scalacOptions ++= scala3Opts :+ "-Werror",
    name := "jorlan-server",
    libraryDependencies ++= Seq(
      // DB
      "org.mariadb.jdbc" % "mariadb-java-client" % mariadbVersion withSources (),
      "io.getquill"     %% "quill-jdbc-zio"      % quillVersion withSources (),
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
    Test / fork                      := true,
    coverageExcludedFiles            := ".*EnvironmentBuilder.*;.*scala/jorlan/Jorlan.*",
    // Skip Scaladoc during packaging — cron4s has a Scala.js annotation that breaks DottyDoc on JVM.
    Compile / doc / sources          := Seq.empty,
  )

//////////////////////////////////////////////////////////////////////////////////////////////////
// Integration Tests
lazy val integration = project
  .enablePlugins(
    AutomateHeaderPlugin,
    com.github.sbt.git.GitVersioning,
  )
  .settings(commonSettings)
  .dependsOn(model, db, server, shell, connectorApi, telegramConnector)
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
    name := "jorlan-shell",
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
    assembly / mainClass             := Some("jorlan.shell.JorlanShell"),
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", xs @ _*) => MergeStrategy.discard
      case x                             => MergeStrategy.preferProject
    },
  )
  .dependsOn(model)

//////////////////////////////////////////////////////////////////////////////////////////////////
// Utility
lazy val util = project
  .settings(commonSettings, scalacOptions ++= scala3Opts :+ "-Werror")

lazy val debianSettings =
  Seq(
    Compile / mainClass                   := Some("jorlan.Jorlan"),
    Debian / name                         := "jorlan-server",
    // Debian versions must start with a digit; sbt-git uses git hash in dev
    Debian / version                      := {
      val v = version.value
      if (v.headOption.exists(_.isDigit)) v else "0.1.0-SNAPSHOT"
    },
    Debian / packageDescription           := "Jorlan Secure Agent Runtime",
    Debian / packageSummary               := "Jorlan Secure Agent Runtime",
    Debian / debianChangelog              := Some(file("debian/changelog")),
    Debian / debianPackageDependencies    += "default-jre-headless (>= 2:1.21)",
    Linux / maintainer                    := "Roberto Leibman <roberto@leibman.net>",
    Linux / daemonUser                    := "jorlan",
    Linux / daemonGroup                   := "jorlan",
    Linux / defaultLinuxInstallLocation   := "/usr/lib",
    Debian / serverLoading                := Some(ServerLoader.Systemd),
    // JVM flags picked up by the generated launch script
    Universal / javaOptions ++= Seq(
      "-Dlogback.configurationFile=/etc/jorlan-server/logback.xml",
      s"-Dconfig.file=/etc/jorlan-server/application.conf",
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
    // Install web frontend assets so the server can serve them directly
    Debian / linuxPackageMappings += {
      val distDir = (ThisBuild / baseDirectory).value / "dist"
      packageMapping(
        (distDir.allPaths --- distDir).get.map { f =>
          f -> s"/usr/lib/jorlan-server/www/${Path.relativeTo(distDir)(f).get}"
        }: _*,
      ).withUser("jorlan").withGroup("jorlan")
    },
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
    Debian / name                       := "jorlan-shell",
    Debian / version                    := {
      val v = version.value
      if (v.headOption.exists(_.isDigit)) v else "0.1.0-SNAPSHOT"
    },
    Debian / packageDescription         := "Jorlan Shell — CLI client for the Jorlan server",
    Debian / packageSummary             := "Jorlan Shell CLI client",
    Debian / debianChangelog            := Some(file("debian/changelog")),
    Debian / debianPackageDependencies  += "default-jre-headless (>= 2:1.21)",
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
lazy val bundlerSettings: Project => Project =
  _.enablePlugins(ScalaJSBundlerPlugin)
    .settings(
      webpack / version := "5.96.1",
      Compile / fastOptJS / artifactPath := ((Compile / fastOptJS / crossTarget).value /
        ((fastOptJS / moduleName).value + "-opt.js")),
      Compile / fullOptJS / artifactPath := ((Compile / fullOptJS / crossTarget).value /
        ((fullOptJS / moduleName).value + "-opt.js")),
      useYarn                                   := true,
      run / fork                                := true,
      Global / scalaJSStage                     := FastOptStage,
      Compile / scalaJSUseMainModuleInitializer := true,
      Test / scalaJSUseMainModuleInitializer    := false,
      Compile / npmDependencies ++= Seq(
      ),
    )

lazy val withCssLoading: Project => Project =
  _.settings(
    /* custom webpack file to include css */
    webpackConfigFile := Some((ThisBuild / baseDirectory).value / "custom.webpack.config.js"),
    Compile / npmDevDependencies ++= Seq(
      "webpack-merge" -> "^6.0.1",
      "css-loader"    -> "^7.1.4",
      "style-loader"  -> "^4.0.0",
      "file-loader"   -> "^6.2.0",
      "url-loader"    -> "^4.1.1",
    ),
  )

lazy val commonWeb: Project => Project =
  _.settings(
    libraryDependencies ++= Seq(
      "net.leibman" %%% "jorlan-stlib"          % stlibVersion withSources (),
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
    startYear                            := Some(2024),
    Compile / unmanagedSourceDirectories := Seq((Compile / scalaSource).value),
    Test / unmanagedSourceDirectories    := Seq((Test / scalaSource).value),
    //    webpackDevServerPort                 := 8009
  )

lazy val web: Project = project
  .dependsOn(model)
  .configure(bundlerSettings)
  .configure(withCssLoading)
  .configure(commonWeb)
  .settings(commonSettings)
  .enablePlugins(
    // AutomateHeaderPlugin,
    com.github.sbt.git.GitVersioning,
    ScalaJSPlugin,
  )
  .settings(
    scalacOptions ++= scala3Opts,
    name            := "jorlan-web",
    // The entire web module compiles to JavaScript (Scala.js) and requires a
    // browser runtime — there are no JVM-runnable tests. Disable scoverage so it
    // doesn't instrument Scala.js bytecode or report 0% coverage.
    coverageEnabled := false,
    libraryDependencies ++= Seq(
      "dev.zio" %%% "zio"      % zioVersion withSources (),
      "dev.zio" %%% "zio-json" % zioJsonVersion withSources (),
    ),
    debugDist := webDistImpl(
      assets = (ThisBuild / baseDirectory).value / "web" / "src" / "main" / "web",
      webpackArtifacts = (Compile / fastOptJS / webpack).value,
      artifactFolder = (Compile / fastOptJS / crossTarget).value,
      outputFolder = (ThisBuild / baseDirectory).value / "debugDist",
      includeSourceMaps = true,
    ),
    dist := webDistImpl(
      assets = (ThisBuild / baseDirectory).value / "web" / "src" / "main" / "web",
      webpackArtifacts = (Compile / fullOptJS / webpack).value,
      artifactFolder = (Compile / fullOptJS / crossTarget).value,
      outputFolder = (ThisBuild / baseDirectory).value / "dist",
      includeSourceMaps = false,
    ),
  )

//////////////////////////////////////////////////////////////////////////////////////////////////
// Root project
lazy val root = project
  .in(file("."))
  .aggregate(
    model,
    connectorApi,
    telegramConnector,
    db,
    ai,
    server,
    shell,
    analytics,
    integration,
    util,
    web,
  )
  .settings(
    name           := "jorlan",
    publish / skip := true,
    version        := "0.1.0",
    headerLicense  := Some(
      HeaderLicense.Custom(
        """Copyright (c) 2026 Roberto Leibman - All Rights Reserved
          |
          |This source code is protected under international copyright law.  All rights
          |reserved and protected by the copyright holders.
          |This file is confidential and only available to authorized individuals with the
          |permission of the copyright holders.  If you encounter this file and do not have
          |permission, please contact the copyright holders and delete this file.""".stripMargin,
      ),
    ),
  )
