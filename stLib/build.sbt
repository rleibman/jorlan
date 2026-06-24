//////////////////////////////////////////////////////////////////////////////////////////////////
// Global stuff
lazy val SCALA = "3.8.4"

val scalajsReactVersion = "4.0.0"
val reactVersion = "^19.2.0"

version := "1.2.0"

enablePlugins(ScalablyTypedConverterGenSourcePlugin)

Global / onChangedBuildSource := ReloadOnSourceChanges
scalaVersion                  := SCALA
Global / scalaVersion         := SCALA

organization     := "net.leibman"
startYear        := Some(2024)
organizationName := "Roberto Leibman"
headerLicense    := Some(HeaderLicense.MIT("2024", "Roberto Leibman", HeaderLicenseStyle.Detailed))
name             := "jorlan-stlib"
useYarn          := true
stOutputPackage  := "net.leibman.jorlan"
stFlavour        := Flavour.ScalajsReact

libraryDependencies ++= Seq(
  "com.github.japgolly.scalajs-react" %%% "core"  % scalajsReactVersion,
  "com.github.japgolly.scalajs-react" %%% "extra" % scalajsReactVersion,
)

dependencyOverrides += "com.github.japgolly.scalajs-react" %%% "core" % scalajsReactVersion

/* javascript / typescript deps */
Compile / npmDependencies ++= Seq(
  "@types/react"     -> reactVersion,
  "@types/react-dom" -> reactVersion,
  "react"            -> reactVersion,
  "react-dom"        -> reactVersion,
  "apexcharts"       -> "^3.51.0",
  "react-apexcharts" -> "^1.8.0",
  "csstype"          -> "^3.2.3",
//  "react-quill"        -> "^2.0.0",
//  "react-markdown"     -> "^10.1.0",
  "@mui/material"   -> "^9.1.0",
  "@emotion/react"  -> "^11.14.0",
  "@emotion/styled" -> "^11.14.1",
)

Test / npmDependencies ++= Seq(
  "react"     -> reactVersion,
  "react-dom" -> reactVersion,
)

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
