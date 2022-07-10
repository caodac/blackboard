
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
  "org.apache.lucene" % "lucene-suggest" % "5.5.5",
  "org.neo4j" % "neo4j" % "3.2.14",
  "org.apache.commons" % "commons-lang3" % "3.9",
  "org.apache.commons" % "commons-text" % "1.6",
  "junit"             % "junit"           % "4.12"  % "test",
  "com.novocode"      % "junit-interface" % "0.11"  % "test"  
)

lazy val javaBuildOptions = Seq(
  "-encoding", "UTF-8"
    //,"-Xlint:-options"
    //,"-Xlint:deprecation"
)

lazy val commonSettings = Seq(
  name := """blackboard""",
  // don't use 2.12! it doesn't load neo4j correctly.
  scalaVersion := "2.11.12",
  version := appVersion
)

lazy val root = (project in file("."))
  .enablePlugins(PlayJava)
  .settings(commonSettings: _*)
  .settings(
     publishArtifact in (Compile, packageDoc) := false,
     publishArtifact in packageDoc := false,
     sources in (Compile,doc) := Seq.empty
  )
  .dependsOn(
    ui,
    index,
    utils,
    disease,
    pharos,
    pubmed,
    umls,
    semmed,
    ct,
    hpo,
    aws,
    chembl,
    idg
  )
  .aggregate(
    ui,
    index,
    utils,
    disease,
    pharos,
    pubmed,
    umls,
    semmed,
    ct,
    hpo,
    aws,
    chembl,
    idg
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
    libraryDependencies += "org.webjars" % "webjars-play_2.11" % "2.6.3",
    libraryDependencies += "org.webjars" % "jquery" % "3.3.1-1",
    libraryDependencies += "org.webjars" % "font-awesome" % "4.7.0",
    javacOptions ++= javaBuildOptions
).dependsOn(buildinfo).aggregate(buildinfo)

lazy val utils =  (project in file("modules/utils"))
  .settings(commonSettings: _*)
  .settings(
    name := "utils",
    libraryDependencies ++= commonDependencies,
    javacOptions ++= javaBuildOptions
).dependsOn(buildinfo).aggregate(buildinfo)

lazy val disease =  (project in file("modules/disease"))
  .settings(commonSettings: _*)
  .settings(
    name := "disease",
    libraryDependencies ++= commonDependencies,
    javacOptions ++= javaBuildOptions
).dependsOn(core,utils).aggregate(core,utils)

lazy val pharos = (project in file("modules/pharos"))
  .enablePlugins(PlayJava)
  .settings(commonSettings: _*)
  .settings(
    name := "pharos",
    libraryDependencies ++= commonDependencies,
    javacOptions ++= javaBuildOptions
).dependsOn(pubmed).aggregate(pubmed)

lazy val idg = (project in file("modules/idg"))
  .enablePlugins(PlayJava)
  .settings(commonSettings: _*)
  .settings(
    name := "idg",
    libraryDependencies ++= commonDependencies,
    javacOptions ++= javaBuildOptions
).dependsOn(index).aggregate(index)

lazy val tripod = (project in file("modules/tripod"))
  .settings(commonSettings: _*)
  .settings(
    name := "tripod",
    javacOptions ++= javaBuildOptions
  )

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
    javacOptions ++= javaBuildOptions,
    publishArtifact in (Compile, packageDoc) := false,
    publishArtifact in packageDoc := false,
    sources in (Compile,doc) := Seq.empty
  ).dependsOn(semmed,index).aggregate(semmed,index)

lazy val umls = (project in file("modules/umls"))
  .enablePlugins(PlayJava)
  .settings(commonSettings: _*)
  .settings(
  name := "umls",
    libraryDependencies ++= commonDependencies,
    javacOptions ++= javaBuildOptions
).dependsOn(mesh,index).aggregate(mesh,index)

lazy val semmed = (project in file("modules/semmed"))
  .enablePlugins(PlayJava)
  .settings(commonSettings: _*)
  .settings(
  name := "semmed",
    libraryDependencies ++= commonDependencies,
    javacOptions ++= javaBuildOptions
  ).dependsOn(umls,ui).aggregate(umls,ui)

lazy val index =  (project in file("modules/index"))
  .settings(commonSettings: _*)
  .settings(
    name := "index",
    libraryDependencies ++= commonDependencies,
    javacOptions ++= javaBuildOptions
).dependsOn(utils).aggregate(utils)

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
  .enablePlugins(PlayJava)
  .settings(commonSettings: _*)
  .settings(
    name := "chembl",
    libraryDependencies ++= commonDependencies,
    javacOptions ++= javaBuildOptions
  ).dependsOn(pubmed).aggregate(pubmed)

lazy val gard = (project in file("modules/gard"))
  .settings(commonSettings: _*)
  .settings(
    name := "gard",
    libraryDependencies ++= commonDependencies,
    javacOptions ++= javaBuildOptions
  ).dependsOn(core).aggregate(core)

lazy val firebase = (project in file("modules/firebase"))
  .settings(commonSettings: _*)
  .settings(
    name := "firebase",
    libraryDependencies ++= commonDependencies,
    libraryDependencies += "com.google.firebase" % "firebase-admin" % "6.5.0",
    libraryDependencies += "com.google.cloud" % "google-cloud-firestore" % "0.68.0-beta",    
    javacOptions ++= javaBuildOptions
  )

lazy val schemaorg = (project in file("modules/schemaorg"))
  .settings(commonSettings: _*)
  .settings(
    name := "schemaorg",
    libraryDependencies ++= commonDependencies,
    libraryDependencies += "com.google.guava" % "guava" % "22.0",
    libraryDependencies += "com.google.code.gson" % "gson" % "2.5",
    libraryDependencies += "com.google.code.findbugs" % "jsr305" % "3.0.1",    
    javacOptions ++= javaBuildOptions
  )

lazy val graphql = (project in file("modules/graphql"))
  .settings(commonSettings: _*)
  .settings(
    name := "graphql",
    libraryDependencies ++= commonDependencies,
    libraryDependencies += "com.graphql-java" % "graphql-java" % "11.0",
    javacOptions ++= javaBuildOptions
  )

lazy val aws = (project in file("modules/aws"))
  .enablePlugins(PlayJava)
  .settings(commonSettings: _*)
  .settings(
    name := "aws",
    libraryDependencies ++= commonDependencies,
    libraryDependencies += "com.amazonaws" % "aws-java-sdk" % "1.11.461",
    javacOptions ++= javaBuildOptions
  ).dependsOn(core).aggregate(core)
