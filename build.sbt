val scala3 = "3.9.0"

val sttpVersion    = "4.0.26"
val upickleVersion = "4.4.3"
val munitVersion   = "1.3.6"

inThisBuild(
  List(
    organization     := "io.github.ticofab",
    organizationName := "Fabio Tiriticco",
    homepage         := Some(url("https://github.com/ticofab/scala-jev-sdk")),
    licenses         := List("Apache-2.0" -> url("https://www.apache.org/licenses/LICENSE-2.0")),
    developers       := List(
      Developer(
        id = "ticofab",
        name = "Fabio Tiriticco",
        email = "fabio.europe@gmail.com",
        url = url("https://github.com/ticofab")
      )
    ),
    scalaVersion       := scala3,
    crossScalaVersions := Seq(scala3),
    versionScheme      := Some("early-semver"),
    scmInfo            := Some(
      ScmInfo(
        url("https://github.com/ticofab/scala-jev-sdk"),
        "scm:git:https://github.com/ticofab/scala-jev-sdk.git",
        Some("scm:git:git@github.com:ticofab/scala-jev-sdk.git")
      )
    )
  )
)

lazy val commonSettings = Seq(
  scalacOptions ++= Seq(
    "-deprecation",
    "-feature",
    "-unchecked",
    "-encoding",
    "utf8",
    "-Wunused:imports",
    "-Wvalue-discard"
  )
)

lazy val root = project
  .in(file("."))
  .aggregate(core, examples)
  .settings(
    name           := "scala-jev-sdk-root",
    publish / skip := true
  )

lazy val core = project
  .in(file("core"))
  .settings(commonSettings)
  .settings(
    name        := "scala-jev-sdk",
    description := "A lightweight, effect-system-agnostic Scala SDK for Jev, TypeSafe AI's System One model.",
    libraryDependencies ++= Seq(
      "com.softwaremill.sttp.client4" %% "core"    % sttpVersion,
      "com.lihaoyi"                   %% "upickle" % upickleVersion,
      "org.scalameta"                 %% "munit"   % munitVersion % Test
    )
  )

lazy val examples = project
  .in(file("examples"))
  .dependsOn(core)
  .settings(commonSettings)
  .settings(
    name           := "scala-jev-sdk-examples",
    publish / skip := true,
    libraryDependencies ++= Seq(
      "com.softwaremill.sttp.client4" %% "cats"        % sttpVersion,
      "org.typelevel"                 %% "cats-effect" % "3.7.1"
    )
  )
