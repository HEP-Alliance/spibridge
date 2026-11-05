ThisBuild / version := "0.1"
ThisBuild / scalaVersion := "2.12.18"

val spinalVersion = "1.15.1"

lazy val spitbridge = (project in file("."))
  .settings(
    name := "spitbridge",
    libraryDependencies ++= Seq(
      "com.github.spinalhdl" %% "spinalhdl-core" % spinalVersion,
      "com.github.spinalhdl" %% "spinalhdl-lib" % spinalVersion,
      compilerPlugin("com.github.spinalhdl" %% "spinalhdl-idsl-plugin" % spinalVersion)
    )
  )

fork := true
