////////////////////////////////////////////////////////////////////////////////////
// Common stuff
addSbtPlugin("com.github.sbt" % "sbt-git" % "2.1.0")
addSbtPlugin("de.heikoseeberger" % "sbt-header"                % "5.10.0")
addSbtPlugin("com.github.cb372"  % "sbt-explicit-dependencies" % "0.3.1")

////////////////////////////////////////////////////////////////////////////////////
// Web client
addSbtPlugin("org.scala-js"                % "sbt-scalajs"              % "1.21.0")
addSbtPlugin("org.portable-scala"          % "sbt-scalajs-crossproject" % "1.3.2")
addSbtPlugin("org.scalablytyped.converter" % "sbt-converter"            % "1.0.0-beta45")

libraryDependencies ++= Seq("org.eclipse.jgit" % "org.eclipse.jgit" % "7.7.0.202606012155-r")
