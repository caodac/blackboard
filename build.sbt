
lazy val branch = "git rev-parse --abbrev-ref HEAD".!!.trim
lazy val commit = "git rev-parse --short HEAD".!!.trim
lazy val author = s"git show --format=%an -s $commit".!!.trim
lazy val buildDate = (new java.text.SimpleDateFormat("yyyyMMdd"))
  .format(new java.util.Date())
lazy val appVersion = "%s-%s-%s".format(branch, buildDate, commit)

lazy val commonDependencies = Seq(
  guice,
  ehcache,
  javaWs,
  javaJdbc,
  "mysql" % "mysql-connector-java" % "5.1.31",
  "org.apache.lucene" % "lucene-facet" % "5.5.5",
  "org.apache.lucene" % "lucene-highlighter" % "5.5.5",
  // can't seem to get beyond version 3.2.1; getting npe in netty!
  "org.neo4j" % "neo4j" % "3.2.1"
)

lazy val javaBuildOptions = Seq(
  "-encoding", "UTF-8"
    //,"-Xlint:-options"
    //,"-Xlint:deprecation"
)

lazy val commonSettings = Seq(
  name := """blackboard""",
  scalaVersion := "2.12.6",
  version := appVersion
)

lazy val root = (project in file("."))
  .enablePlugins(PlayJava)
  .settings(commonSettings: _*)
  .dependsOn(
    ui,
    pharos,
    biothings,
    beacons,
    pubmed,
    umls,
    semmed,
    ct,
    hpo,
    chembl
  )
  .aggregate(
    ui,
    pharos,
    biothings,
    beacons,
    pubmed,
    umls,
    semmed,
    ct,
    hpo,
    chembl
  )

lazy val buildinfo = (project in file("modules/build"))
  .settings(commonSettings: _*)
  .settings(name := "buildinfo",
    sourceGenerators in Compile += sourceManaged in Compile map { dir =>
      val file = dir / "BuildInfo.java"
      IO.write(file, """
package blackboard;
public class BuildInfo { 
   public static final String BRANCH = "%s";
   public static final String DATE = "%s";
   public static final String COMMIT = "%s";
   public static final String TIME = "%s";
   public static final String AUTHOR = "%s";
}
""".format(branch, buildDate, commit, new java.util.Date(), author))
      Seq(file)
    }
)

lazy val core =  (project in file("modules/core"))
  .enablePlugins(PlayJava)
  .settings(commonSettings: _*)
  .settings(
    name := "core",
    libraryDependencies ++= commonDependencies,
    javacOptions ++= javaBuildOptions
).dependsOn(buildinfo).aggregate(buildinfo)

lazy val ui =  (project in file("modules/ui"))
  .enablePlugins(PlayJava)
  .settings(commonSettings: _*)
  .settings(
    name := "ui",
    libraryDependencies ++= commonDependencies,
    libraryDependencies += "org.webjars" % "webjars-play_2.12" % "2.6.3",
    libraryDependencies += "org.webjars" % "jquery" % "3.3.1-1",
    libraryDependencies += "org.webjars" % "font-awesome" % "4.7.0",
    javacOptions ++= javaBuildOptions
).dependsOn(buildinfo).aggregate(buildinfo)

lazy val pharos = (project in file("modules/pharos"))
  .enablePlugins(PlayJava)
  .settings(commonSettings: _*)
  .settings(
    name := "pharos",
    libraryDependencies ++= commonDependencies,
    javacOptions ++= javaBuildOptions
).dependsOn(pubmed).aggregate(pubmed)

lazy val biothings = (project in file("modules/biothings"))
  .settings(commonSettings: _*)
  .settings(
    name := "biothings",
    libraryDependencies ++= commonDependencies,
    javacOptions ++= javaBuildOptions
).dependsOn(core).aggregate(core)

lazy val beacons = (project in file("modules/beacons"))
  .settings(commonSettings: _*)
  .settings(
  name := "beacons",
    libraryDependencies ++= commonDependencies,
    javacOptions ++= javaBuildOptions
).dependsOn(core).aggregate(core)

lazy val mesh = (project in file("modules/mesh"))
  .enablePlugins(PlayJava)
  .settings(commonSettings: _*)
  .settings(
  name := "mesh",
    libraryDependencies ++= commonDependencies,
    javacOptions ++= javaBuildOptions
).dependsOn(core, ui).aggregate(core, ui)

lazy val pubmed = (project in file("modules/pubmed"))
  .enablePlugins(PlayJava)
  .settings(commonSettings: _*)
  .settings(
  name := "pubmed",
    libraryDependencies ++= commonDependencies,
    libraryDependencies +=   "org.json" % "json" % "20090211",
    javacOptions ++= javaBuildOptions
).dependsOn(mesh, ui).aggregate(mesh, ui)

lazy val umls = (project in file("modules/umls"))
  .enablePlugins(PlayJava)
  .settings(commonSettings: _*)
  .settings(
  name := "umls",
    libraryDependencies ++= commonDependencies,
    javacOptions ++= javaBuildOptions
).dependsOn(mesh).aggregate(mesh)

lazy val semmed = (project in file("modules/semmed"))
  .enablePlugins(PlayJava)
  .settings(commonSettings: _*)
  .settings(
  name := "semmed",
    libraryDependencies ++= commonDependencies,
    javacOptions ++= javaBuildOptions
  ).dependsOn(umls,pubmed).aggregate(umls,pubmed)

lazy val ct = (project in file("modules/ct"))
  .enablePlugins(PlayJava)
  .settings(commonSettings: _*)
  .settings(
    name := "ct",
    libraryDependencies ++= commonDependencies,
    javacOptions ++= javaBuildOptions
  ).dependsOn(umls).aggregate(umls)

lazy val hpo = (project in file("modules/hpo"))
  .enablePlugins(PlayJava)
  .settings(commonSettings: _*)
  .settings(
    name := "hpo",
    libraryDependencies ++= commonDependencies,
    javacOptions ++= javaBuildOptions
  ).dependsOn(core).aggregate(core)

lazy val chembl = (project in file("modules/chembl"))
  .settings(commonSettings: _*)
  .settings(
    name := "chembl",
    libraryDependencies ++= commonDependencies,
    javacOptions ++= javaBuildOptions
  ).dependsOn(core).aggregate(core)
