# Collaboration web editor
## Scala, Akka, Websockets
Simple technology demonstrator for using websockets and Akka Streams with Akka Persistence.
Uses dockerized Cassandra.

Loosely inspired by Google Wave project.

Uses Quill.js which does not perform well at all. Needs to be replaced

How run:

`docker-compose up` //  starts Cassandra

accessing the service

`http://localhost:8080/init/user/1`

where `user` will be the user login name and `1` is the number of collaboration sheet.
