name := "devsearch-ast"

shellPrompt := { state => "[\033[36m" + name.value + "\033[0m] $ " }

version := "0.1"

scalaVersion := "2.10.4"

libraryDependencies += "com.github.javaparser" % "javaparser-core" % "2.0.0"

libraryDependencies += "org.scalatest" %% "scalatest" % "2.1.7" % "test"

