package service.actor

import akka.actor.testkit.typed.scaladsl.ActorTestKit
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.typed.ActorRef
import akka.persistence.testkit.PersistenceTestKitPlugin
import akka.persistence.testkit.scaladsl.PersistenceTestKit
import com.typesafe.config.ConfigFactory
import concurentDocs.app.domain.TextEditorDomain.DeltaMessage
import concurentDocs.app.domain.TextEditorDomain.InsertString
import concurentDocs.app.domain.TextEditorDomain.Retain
import concurentDocs.service.actor.CollabRoomPersistentActor
import concurentDocs.service.actor.CollabRoomPersistentActor.PersistCommand
import concurentDocs.service.actor.CollabRoomPersistentActor.PersistCommand.AddAction
import concurentDocs.service.actor.CollabRoomPersistentActor.PersistEvent.ActionAdded
import org.scalatest.BeforeAndAfterAll
import org.scalatest.BeforeAndAfterEach
import org.scalatest.wordspec.AnyWordSpecLike

import java.util.UUID

class CollabRoomPersistentActorTest
    extends ScalaTestWithActorTestKit(PersistenceTestKitPlugin.config.withFallback(ConfigFactory.defaultApplication()))
    with AnyWordSpecLike
    with BeforeAndAfterAll
    with BeforeAndAfterEach {

  val persistenceTestKit: PersistenceTestKit = PersistenceTestKit(testKit.system)

  override def beforeEach(): Unit =
    persistenceTestKit.clearAll()

  override def afterAll(): Unit =
    testKit.shutdownTestKit()

  "CollabRoomPersistentActor" should {

    "persist an action when receiving AddAction command" in {

      val roomId                                 = 1 // Example room ID
      val persistActor: ActorRef[PersistCommand] = spawn(CollabRoomPersistentActor(roomId))

      val testDelta = DeltaMessage(List(InsertString("test string"), Retain(14)))
      val addAction = AddAction(testDelta)

      persistActor ! addAction

      persistenceTestKit.persistedInStorage(persistActor.path.name)
      persistenceTestKit.expectNextPersisted(s"collabroom-$roomId", ActionAdded(testDelta))
    }
  }

}
