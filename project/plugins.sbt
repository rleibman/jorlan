
////////////////////////////////////////////////////////////////////////////////////
// Common stuff
addSbtPlugin("com.github.sbt"    % "sbt-git"                   % "2.1.0")
addSbtPlugin("de.heikoseeberger" % "sbt-header"                % "5.10.0")
addSbtPlugin("com.github.sbt"    % "sbt-native-packager"       % "1.11.7")
addSbtPlugin("com.eed3si9n"      % "sbt-buildinfo"             % "0.13.1")
addSbtPlugin("org.scalameta"     % "sbt-scalafmt"              % "2.6.1")
addSbtPlugin("com.github.cb372"  % "sbt-explicit-dependencies" % "0.3.1")
addSbtPlugin("com.typesafe"      % "sbt-mima-plugin"           % "1.1.5")

////////////////////////////////////////////////////////////////////////////////////
// Web client
addSbtPlugin("com.github.ghostdogpr" % "caliban-codegen-sbt"      % "3.1.1")

////////////////////////////////////////////////////////////////////////////////////
// Server
addSbtPlugin("io.spray" % "sbt-revolver" % "0.10.0")

////////////////////////////////////////////////////////////////////////////////////
// Testing
//addSbtPlugin("io.stryker-mutator" % "sbt-stryker4s" % "0.19.1")
addSbtPlugin("org.scoverage"      % "sbt-scoverage" % "2.4.4")

libraryDependencies ++= Seq("org.eclipse.jgit" % "org.eclipse.jgit" % "7.6.0.202603022253-r")
