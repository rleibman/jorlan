////////////////////////////////////////////////////////////////////////////////////
// Common stuff
addSbtPlugin("com.github.sbt"    % "sbt-git"                   % "2.1.0")
addSbtPlugin("de.heikoseeberger" % "sbt-header"                % "5.10.0")
addSbtPlugin("com.github.sbt"    % "sbt-native-packager"       % "1.11.7")
addSbtPlugin("com.eed3si9n"      % "sbt-buildinfo"             % "0.13.1")
addSbtPlugin("org.scalameta"     % "sbt-scalafmt"              % "2.6.1")
libraryDependencies += "org.scalameta"       %% "scalafmt-dynamic" % "3.11.1"
addSbtPlugin("com.github.cb372"  % "sbt-explicit-dependencies" % "0.3.1")
addSbtPlugin("com.typesafe"      % "sbt-mima-plugin"           % "1.1.5")

////////////////////////////////////////////////////////////////////////////////////
// Web client
addSbtPlugin("org.scala-js"          % "sbt-scalajs"              % "1.21.0")
addSbtPlugin("com.github.ghostdogpr" % "caliban-codegen-sbt"      % "3.1.2")
addSbtPlugin("org.portable-scala"    % "sbt-scalajs-crossproject" % "1.3.2")
addSbtPlugin("ch.epfl.scala"         % "sbt-scalajs-bundler"      % "0.21.1")

////////////////////////////////////////////////////////////////////////////////////
// Shell
addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "2.3.1")

////////////////////////////////////////////////////////////////////////////////////
// Server
addSbtPlugin("io.spray" % "sbt-revolver" % "0.10.0")

////////////////////////////////////////////////////////////////////////////////////
// Testing
//addSbtPlugin("io.stryker-mutator" % "sbt-stryker4s" % "0.19.1")
addSbtPlugin("org.scoverage" % "sbt-scoverage" % "2.4.4")
addSbtPlugin("nl.gn0s1s"     % "sbt-dotenv"    % "3.2.0")

libraryDependencies ++= Seq("org.eclipse.jgit" % "org.eclipse.jgit" % "7.7.0.202606012155-r")
