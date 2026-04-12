ThisBuild / version := "0.1.0"
ThisBuild / scalaVersion := "2.13.14"
ThisBuild / organization := "local.spinalhdlvdp"

val spinalVersion = "1.12.3"
val spinalCore = "com.github.spinalhdl" %% "spinalhdl-core" % spinalVersion
val spinalLib = "com.github.spinalhdl" %% "spinalhdl-lib" % spinalVersion
val spinalIdslPlugin = compilerPlugin("com.github.spinalhdl" %% "spinalhdl-idsl-plugin" % spinalVersion)

lazy val spinalhdlvdp = (project in file("."))
  .settings(
    name := "spinalhdlVDP",
    Compile / scalaSource := baseDirectory.value / "hw" / "spinal",
    libraryDependencies ++= Seq(spinalCore, spinalLib, spinalIdslPlugin)
  )
