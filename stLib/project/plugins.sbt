////////////////////////////////////////////////////////////////////////////////////
// Common stuff
addSbtPlugin("com.github.sbt" % "sbt-git"    % "2.1.0")
addSbtPlugin("com.github.sbt" % "sbt-header" % "5.11.0")
// No sbt2 build of sbt-explicit-dependencies.
// addSbtPlugin("com.github.cb372"  % "sbt-explicit-dependencies" % "0.3.1")

////////////////////////////////////////////////////////////////////////////////////
// Web client
addSbtPlugin("org.scala-js"                % "sbt-scalajs"              % "1.22.0")
addSbtPlugin("org.portable-scala"          % "sbt-scalajs-crossproject" % "1.4.0")
addSbtPlugin("org.scalablytyped.converter" % "sbt-converter"            % "1.0.0-beta45-local777-SNAPSHOT")

libraryDependencies ++= Seq("org.eclipse.jgit" % "org.eclipse.jgit" % "7.7.1.202607240634-r")
