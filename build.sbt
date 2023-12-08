ThisBuild / version := "0.1.0-SNAPSHOT"

ThisBuild / scalaVersion := "2.13.10"

val akkaVersion = "2.7.0"
val akkaHttpVersion = "10.5.3"
val scalaTestVersion = "3.2.15"
val cassandraPersistenceVersion = "1.1.1"
val tinkerPopVersion = "3.4.13"

val libraries = Seq(
  "com.typesafe.akka" %% "akka-actor-typed" % akkaVersion,
  "com.typesafe.akka" %% "akka-stream-typed" % akkaVersion,
  "com.typesafe.akka" %% "akka-persistence-typed" % akkaVersion,
  "com.typesafe.akka" %% "akka-http" % akkaHttpVersion,
  "com.typesafe.akka" %% "akka-http-caching" % akkaHttpVersion,
  "com.typesafe.akka" %% "akka-http-spray-json" % akkaHttpVersion,
  "ch.qos.logback" % "logback-classic" % "1.3.11",

  "org.java-websocket" % "Java-WebSocket" % "1.3.0", // just for the demo client
  "org.iq80.leveldb" % "leveldb" % "0.12", // LevelDB library
  "com.typesafe.akka" %% "akka-persistence-cassandra" % cassandraPersistenceVersion,   //  Cassandra for journaling - not working
  "com.typesafe.akka" %% "akka-persistence" % akkaVersion,
  "com.typesafe.akka" %% "akka-persistence-query" % akkaVersion,
  "com.typesafe.akka" %% "akka-cluster-tools" % akkaVersion,
  "com.esri.geometry" % "esri-geometry-api" % "2.2.4",    //  some Cassandra BS
  "org.apache.tinkerpop" % "gremlin-core" % tinkerPopVersion,    // more Cassandra BS
  "org.apache.tinkerpop" % "tinkergraph-gremlin" % tinkerPopVersion,  // even more Cassandra BS

  "org.fusesource.leveldbjni" % "leveldbjni-all" % "1.8",
  "io.altoo" %% "akka-kryo-serialization" % "2.5.2",

  "org.scalatest" %% "scalatest" % scalaTestVersion % Test,
  "com.typesafe.akka" %% "akka-actor-testkit-typed" % akkaVersion % Test,
  "com.typesafe.akka" %% "akka-stream-testkit" % akkaVersion % Test,
  "com.typesafe.akka" %% "akka-persistence-testkit" % akkaVersion % Test,
)

lazy val root = (project in file("."))
  .settings(
    name := "parallel_edit",
    libraryDependencies ++= libraries
  )
