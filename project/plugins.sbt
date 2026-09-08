////////////////////////////////////////////////////////////////////////////////////
// Common stuff
addSbtPlugin("com.github.sbt"    % "sbt-git"                   % "2.1.0")
addSbtPlugin("com.github.sbt"    % "sbt-header"                % "5.11.0")
addSbtPlugin("com.github.sbt"    % "sbt-native-packager"       % "1.11.7")
addSbtPlugin("com.eed3si9n"      % "sbt-buildinfo"             % "0.13.1")
addSbtPlugin("org.scalameta"     % "sbt-scalafmt"              % "2.6.2")
// sbt2: sbt-scalafmt brings its own scalafmt-dynamic. Pinning it here drags in _2.13 artifacts alongside the
// build's _3 ones and fails the load with "Conflicting cross-version suffixes: scala-xml, jsoniter-scala-core".
// libraryDependencies += "org.scalameta" %% "scalafmt-dynamic" % "3.11.4"
// No sbt2 build of sbt-explicit-dependencies.
// addSbtPlugin("com.github.cb372"  % "sbt-explicit-dependencies" % "0.3.1")
addSbtPlugin("com.typesafe"      % "sbt-mima-plugin"           % "1.1.6")

////////////////////////////////////////////////////////////////////////////////////
// Web client
addSbtPlugin("org.scala-js"          % "sbt-scalajs"              % "1.22.0")
addSbtPlugin("com.github.ghostdogpr" % "caliban-codegen-sbt"      % "3.1.5")
addSbtPlugin("org.portable-scala"    % "sbt-scalajs-crossproject" % "1.4.0")
// sbt-scalajs-esbuild-web has no sbt2 build (the me.ptrdom org is sbt1-only). Bundling is vite now --
// see runViteBuild in build.sbt and web/vite.config.js.
// addSbtPlugin("me.ptrdom"             % "sbt-scalajs-esbuild-web"  % "0.1.3")

////////////////////////////////////////////////////////////////////////////////////
// Shell
addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "2.5.0")

////////////////////////////////////////////////////////////////////////////////////
// Server
// No sbt2 build of sbt-revolver, so `reStart` is gone; use `run`.
// addSbtPlugin("io.spray" % "sbt-revolver" % "0.10.0")

////////////////////////////////////////////////////////////////////////////////////
// Testing
addSbtPlugin("org.scoverage" % "sbt-scoverage" % "2.4.4")
addSbtPlugin("nl.gn0s1s"     % "sbt-dotenv"    % "3.3.0")

libraryDependencies ++= Seq("org.eclipse.jgit" % "org.eclipse.jgit" % "7.7.1.202607240634-r")
