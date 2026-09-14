//////////////////////////////////////////////////////////////////////////////////////////////////
// Global stuff
lazy val SCALA = "3.8.4"

val scalajsReactVersion = "4.0.0"

version := "1.5.0"

enablePlugins(ScalablyTypedConverterExternalNpmPlugin)

Global / onChangedBuildSource := ReloadOnSourceChanges
scalaVersion                  := SCALA
Global / scalaVersion         := SCALA

organization     := "net.leibman"
startYear        := Some(2024)
organizationName := "Roberto Leibman"
headerLicense    := Some(HeaderLicense.MIT("2024", "Roberto Leibman", HeaderLicenseStyle.Detailed))
name             := "jorlan-stlib"
stOutputPackage  := "net.leibman.jorlan"
stFlavour        := Flavour.ScalajsReact

externalNpm := baseDirectory.value

// sbt 2 has cross-platform support built in: `%%` resolves the Scala.js (_sjs1_3) artifacts here.
libraryDependencies ++= Seq(
  "com.github.japgolly.scalajs-react" %% "core"  % scalajsReactVersion,
  "com.github.japgolly.scalajs-react" %% "extra" % scalajsReactVersion,
)

dependencyOverrides += "com.github.japgolly.scalajs-react" %% "core" % scalajsReactVersion

// The converter pins scalajs-react 2.1.3 in every generated facade, while we compile against 4.0.0. sbt 2 turns that
// major-version eviction into an error (sbt 1 only warned).
libraryDependencySchemes ++= Seq(
  "com.github.japgolly.scalajs-react" % "core_sjs1_3"  % VersionScheme.Always,
  "com.github.japgolly.scalajs-react" % "extra_sjs1_3" % VersionScheme.Always,
)

/* disabled because it somehow triggers many warnings */
scalaJSLinkerConfig ~= (_.withSourceMap(false))

// focus only on these libraries
stMinimize := Selection.AllExcept(
//  "react-quill", "react-markdown",
  "@mui/material",
  "marked",
  "react-apexcharts",
)

stIgnore ++= List(
)

licenses += ("MIT", uri("http://opensource.org/licenses/MIT"))

doc / sources := Nil

publishTo := Some(
  "GitHub Package Registry" at "https://maven.pkg.github.com/rleibman/jorlan",
)

credentials += Credentials(
  "GitHub Package Registry",
  "maven.pkg.github.com",
  "rleibman",
  sys.env.getOrElse("GITHUB_TOKEN", ""),
)
