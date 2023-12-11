package service.actor

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import concurentDocs.app.domain.TextEditorDomain
import concurentDocs.app.domain.UserDomain.User
import concurentDocs.service.actor.CollabRoomActor
import concurentDocs.service.actor.CollabRoomActor.CollabRoomEvent
import concurentDocs.service.actor.CollabRoomPersistentActor.PersistCommand
import org.scalatest.BeforeAndAfterEach
import org.scalatest.wordspec.AnyWordSpecLike

import scala.collection.immutable.HashMap

class CollabRoomActorTest extends ScalaTestWithActorTestKit with AnyWordSpecLike with BeforeAndAfterEach {

  "A collab room actor" should {
    import CollabRoomEvent._
    import TextEditorDomain._

    "login a new user" in {
      val editorId = 1
      val sysUser = User("sys-user")
      val loginUser = User("login-user")
      val mockPersistProbe = testKit.createTestProbe[PersistCommand]()
      val loginUserProbe = testKit.createTestProbe[CollabRoomEvent]()
      val collabRoom = testKit.spawn(CollabRoomActor.initialized(editorId, HashMap(), mockPersistProbe.ref, sysUser))

      collabRoom ! UserLogin(loginUser, loginUserProbe.ref)

      loginUserProbe.expectMessageType[CollabFlow]
    }

    "broadcast messages to all but sender" in {
      val editorId = 1
      val sysUser = User("sys-user")
      val sendingUser = User("sending-user")
      val receivingingUser = User("receiving-user")
      val mockPersistProbe = testKit.createTestProbe[PersistCommand]()
      val sendingUserProbe = testKit.createTestProbe[CollabRoomEvent]()
      val receivingingProbe = testKit.createTestProbe[CollabRoomEvent]()
      val participants = HashMap(
        sendingUser -> sendingUserProbe.ref,
        receivingingUser -> receivingingProbe.ref
      )
      val someEditorAction = DeltaMessage(List(InsertString("a"), Retain(1)))
      val someEditorEvent = EditorEvent(sendingUser, someEditorAction)
      val collabRoom = testKit.spawn(CollabRoomActor.initialized(editorId, participants, mockPersistProbe.ref, sysUser))

      collabRoom ! someEditorEvent

      receivingingProbe.expectMessage(someEditorEvent)
      sendingUserProbe.expectNoMessage()
    }
  }
}
