//////////////////////////////////////////////////////////////////////////////////////////////////
// Global stuff
lazy val SCALA = "3.8.4"

val scalajsReactVersion = "4.0.0"

version := "1.3.0"

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

libraryDependencies ++= Seq(
  "com.github.japgolly.scalajs-react" %%% "core"  % scalajsReactVersion,
  "com.github.japgolly.scalajs-react" %%% "extra" % scalajsReactVersion,
)

dependencyOverrides += "com.github.japgolly.scalajs-react" %%% "core" % scalajsReactVersion

/* disabled because it somehow triggers many warnings */
scalaJSLinkerConfig ~= (_.withSourceMap(false))

// focus only on these libraries
stMinimize := Selection.AllExcept(
//  "react-quill", "react-markdown",
  "@mui/material",
  "react-apexcharts",
)

stIgnore ++= List(
)

licenses += ("MIT", url("http://opensource.org/licenses/MIT"))

doc / sources := Nil

//stReactEnableTreeShaking := true
publishTo := Some(
  "GitHub Package Registry" at "https://maven.pkg.github.com/rleibman/jorlan",
)

credentials += Credentials(
  "GitHub Package Registry",
  "maven.pkg.github.com",
  "rleibman",
  sys.env.getOrElse("GITHUB_TOKEN", ""),
)
