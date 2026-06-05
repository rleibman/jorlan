////////////////////////////////////////////////////////////////////////////////////
// Common Stuff

lazy val buildTime: SettingKey[String] = SettingKey[String]("buildTime", "time of build").withRank(KeyRanks.Invisible)

//////////////////////////////////////////////////////////////////////////////////////////////////
// Global stuff
ThisBuild / resolvers += Resolver.sonatypeCentralSnapshots
ThisBuild / resolvers += "GitHub Packages rleibman" at "https://maven.pkg.github.com/rleibman/zio-auth"
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

import scala.concurrent.duration.*
Global / watchAntiEntropy := 1.second

ThisBuild / libraryDependencySchemes += "dev.zio" %% "zio-json" % VersionScheme.Always

//////////////////////////////////////////////////////////////////////////////////////////////////
// Shared settings

lazy val start = TaskKey[Unit]("start")
lazy val dist = TaskKey[File]("dist")
lazy val debugDist = TaskKey[File]("debugDist")

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

val calibanClientVersion = "3.1.2"
val calibanVersion = "3.1.2"
val commonsCodecVersion = "1.21.0"
val courierVersion = "4.0.0-RC1"
val cron4sVersion = "0.8.2"
val dispatchHttpVersion = "2.0.0"
val flywayVersion = "12.7.0"
val izumiReflectVersion = "3.0.9"
val jaxbApiVersion = "2.3.1"
val jsoniterVersion = "2.38.9"
val justSemverCoreVersion = "1.3.0"
val jwtCirceVersion = "11.0.4"
val jwtZioJsonVersion = "11.0.4"
val langchain4jOllamaVersion = "1.15.1"
val langchainCoreVersion = "1.15.1"
val langchainLibrariesVersion = "1.15.1-beta25"
val lanternaVersion = "3.1.5"
val logbackVersion = "1.5.34"
val mariadbVersion = "3.5.8"
val openPdfVersion = "3.0.3"
val qdrantVersion = "1.21.4"
val quillVersion = "4.8.6"
val scalablytypedRuntimeVersion = "2.4.2"
val scalacssVersion = "1.0.0"
val scalaJavaTimeVersion = "2.6.0"
val sttpClient4Version = "4.0.25"
val testContainerVersion = "0.44.1"
val zioAuth = "3.1.5"
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
  .dependsOn(model, db, ai, analytics)
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
    coverageExcludedFiles            := ".*EnvironmentBuilder.*;.*scala/jorlan/Jorlan.*",
  )

//////////////////////////////////////////////////////////////////////////////////////////////////
// Integration Tests
lazy val integration = project
  .enablePlugins(
    AutomateHeaderPlugin,
    com.github.sbt.git.GitVersioning,
  )
  .settings(commonSettings)
  .dependsOn(model, db, server, shell)
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
  )
  .settings(
    scalacOptions ++= scala3Opts :+ "-Werror",
    name := "jorlan-shell",
    commonSettings,
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
    Compile / mainClass         := Some("jorlan.Jorlan"),
    Debian / name               := "jorlan",
    Debian / packageDescription := "Jorlan Secure Agent Runtime",
    Debian / packageSummary     := "Jorlan Secure Agent Runtime",
    Debian / debianChangelog    := Some(file("debian/changelog")),
    Linux / maintainer          := "Roberto Leibman <roberto@leibman.net>",
    Linux / daemonUser          := "jorlan",
    Linux / daemonGroup         := "jorlan",
    Debian / serverLoading      := Some(ServerLoader.Systemd),
    // Configure JVM to use logback.xml from /etc/jorlan-server
    Universal / javaOptions ++= Seq(
      "-Dlogback.configurationFile=/etc/jorlan-server/logback.xml",
    ),
    // Map application.conf template
    Universal / mappings += {
      val src = sourceDirectory.value
      val conf = src / "templates" / "application.conf"
      conf -> "conf/application.conf"
    },
    // Map logback.xml template
    Universal / mappings += {
      val src = sourceDirectory.value
      val logback = src / "templates" / "logback.xml"
      logback -> "conf/logback.xml"
    },
    // Map the entire dist directory to /data/www/app.jorlan.com/html
    Universal / mappings ++= {
      val distDir = (ThisBuild / baseDirectory).value / "dist"
      if (distDir.exists()) {
        (distDir.allPaths --- distDir) pair Path.rebase(distDir, "www/")
      } else {
        Seq.empty
      }
    },
    // Install www content to the web directory
    Linux / defaultLinuxInstallLocation := "/opt",
    // Additional package mapping for www content
    Debian / linuxPackageMappings += {
      val distDir = (ThisBuild / baseDirectory).value / "dist"
      packageMapping(
        (distDir.allPaths --- distDir).get.map { f =>
          f -> s"/data/www/app.jorlan.com/html/${Path.relativeTo(distDir)(f).get}"
        }: _*,
      ).withUser("jorlan").withGroup("jorlan")
    },
    // Install configuration files to /etc/jorlan-server/ for easy editing
    Debian / linuxPackageMappings += {
      val src = sourceDirectory.value
      val confFile = src / "templates" / "application.conf"
      val logbackFile = src / "templates" / "logback.xml"
      packageMapping(
        confFile    -> "/etc/jorlan-server/application.conf",
        logbackFile -> "/etc/jorlan-server/logback.xml",
      ).withUser("jorlan").withGroup("jorlan").withPerms("0644").withConfig()
    },
    // Add custom maintainer scripts to create log directory
    Debian / maintainerScripts := {
      val scripts:  Map[String, Seq[String]] = (Debian / maintainerScripts).value
      val postinst: Seq[String] = scripts.getOrElse("postinst", Seq.empty)

      // Add log directory creation before chown commands
      val updatedPostinst: Seq[String] = postinst.map { line =>
        if (line.contains("chown jorlan:jorlan '/var/log/jorlan-server'")) {
          Seq(
            "mkdir -p '/var/log/jorlan-server'",
            "chown -R jorlan:jorlan '/var/log/jorlan-server'",
            "chmod 755 '/var/log/jorlan-server'",
          ).mkString("\n")
        } else {
          line
        }
      }

      scripts + ("postinst" -> updatedPostinst)
    },
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
  )

//////////////////////////////////////////////////////////////////////////////////////////////////
// Root project
lazy val root = project
  .in(file("."))
  .aggregate(
    model,
    db,
    ai,
    server,
    shell,
    analytics,
    integration,
    util,
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
