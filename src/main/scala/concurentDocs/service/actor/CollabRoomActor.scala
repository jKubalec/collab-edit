package concurentDocs.service.actor

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior, PostStop, Terminated}
import akka.stream.scaladsl.Flow
import akka.util.Timeout
import concurentDocs.app.domain.TextEditorDomain
import concurentDocs.service.actor.CollabRoomPersistentActor.PersistCommand
import concurentDocs.service.internals.EditorFlow
import org.slf4j.Logger
import concurentDocs.app.domain.UserDomain.User

import scala.collection.immutable.HashMap
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.DurationInt
import scala.util.{Failure, Success}

object CollabRoomActor {

  val BUFFER_SIZE = 2048
  sealed trait CollabRoomEvent

  object CollabRoomEvent {
    import concurentDocs.app.domain.TextEditorDomain.{DeltaMessage, EditorDelta, EditorMessage}
    case class EditorEvent(user: User, event: DeltaMessage) extends CollabRoomEvent

    case class UserLogout(user: User) extends CollabRoomEvent

    case class UserLogin(user: User, replyTo: ActorRef[CollabRoomEvent]) extends CollabRoomEvent

    case class Ping(user: User, replyTo: ActorRef[CollabRoomEvent]) extends CollabRoomEvent

    case class UserFlowCreated(user: User, requestorActor: ActorRef[CollabRoomEvent], chatFlowActor: ActorRef[CollabRoomEvent]) extends CollabRoomEvent

    case class CollabFlow(flow: Flow[EditorMessage, EditorMessage, _]) extends CollabRoomEvent

    case class CollabError(e: Throwable) extends CollabRoomEvent

    case class ContentRequest(replyTo: ActorRef[CollabRoomEvent]) extends CollabRoomEvent

    case class EditorContent(content: String, deltas: List[EditorDelta], user: User) extends CollabRoomEvent

    case class PersistedContent(content: String, deltas: List[EditorDelta], requestor: ActorRef[CollabRoomEvent]) extends CollabRoomEvent

    case class ClearContent(user: User) extends CollabRoomEvent

    case object PersistenceAlive extends CollabRoomEvent

    case class CollabActorFailure(description: String) extends CollabRoomEvent

    case object UpdateContent extends CollabRoomEvent
  }

  def broadcast(from: User, event: CollabRoomEvent, participants: Map[User, ActorRef[CollabRoomEvent]]): Unit =
    participants.filter(_._1 != from).foreach(_._2 ! event)

  def apply(editorId: Int): Behavior[CollabRoomEvent] = uninitialized(editorId, None)

  def uninitialized(editorId: Int, persistActorOpt: Option[ActorRef[PersistCommand]]): Behavior[CollabRoomEvent] = Behaviors.setup { context =>
    import CollabRoomEvent._
    implicit val timeout: Timeout = 5.seconds

    context.log.info(s"[collabroom-$editorId] starting uninitialized editor ID: $editorId")
    val persistActor = persistActorOpt.getOrElse(context.spawn(CollabRoomPersistentActor(editorId), s"persist-actor-$editorId"))
    context.ask(persistActor, ref => PersistCommand.Ping(ref)) {
      case Success(msg) => msg match {
        case ok @ PersistenceAlive => ok
        case other => CollabActorFailure(s"Unexpected message from persistence actor: $other")
      }
      case Failure(exception) => CollabActorFailure(exception.toString)
    }
    waitingForInit(editorId, persistActor, HashMap(), User(s"editor-ID-$editorId"))
  }

  def waitingForInit(editorId: Int,
                     persistActor: ActorRef[PersistCommand],
                     participants: HashMap[User, ActorRef[CollabRoomEvent]],
                     sysUser: User,
                     persistenceInitiated: Boolean = false
                    ): Behavior[CollabRoomEvent] =
    Behaviors.withStash(BUFFER_SIZE) { buffer =>
      Behaviors.receive { (context, message) =>
        import CollabRoomEvent._

        if (participants.isEmpty)
          context.log.info(s"[collabroom-$editorId] Waiting to initialize: users${if (!persistenceInitiated) ", persistence"}.")
        else
          context.log.info(s"[collabroom-$editorId] Waiting to initialize persistence.")

        message match {
          case PersistenceAlive =>
            context.log.info(s"[collabroom-$editorId] Persistence for editor $editorId is up.")
            if (participants.nonEmpty)
              buffer.unstashAll(initialized(editorId, participants, persistActor, sysUser))
            else
              buffer.unstashAll(waitingForInit(editorId, persistActor, participants, sysUser, persistenceInitiated = true))
          case UserLogin(user, replyTo) =>
            context.log.info(s"[collabroom-$editorId] user $user logging in.")
            val flow = EditorFlow.getEditorFlow(user, replyTo, context.self)(context.log)
            replyTo ! CollabFlow(flow)
            Behaviors.same
          case UserFlowCreated(user, _, chatFlowActor) =>
            val newParticipants = participants + (user -> chatFlowActor)
            context.log.debug(s"[collabroom-$editorId] new participants map $newParticipants")
            if (persistenceInitiated)
              buffer.unstashAll(initialized(editorId, newParticipants, persistActor, sysUser))
            else
              buffer.unstashAll(waitingForInit(editorId, persistActor, newParticipants, sysUser, persistenceInitiated))
          case other =>
            buffer.stash(other)
            Behaviors.same
        }
      }
    }

  def initialized(editorId: Int,
                  participants: HashMap[User, ActorRef[CollabRoomEvent]],
                  persistActor: ActorRef[PersistCommand],
                  sysUser: User,
                 ): Behavior[CollabRoomEvent] = Behaviors.setup { context =>
    import CollabRoomEvent._
    import PersistCommand._
    import concurentDocs.app.domain.TextEditorDomain._

    implicit val log: Logger = context.log

    implicit val timeout: Timeout = 500.millis
    implicit val ec: ExecutionContext = context.executionContext

    log.info(s"[collabroom-$editorId] Initializing CollabRoom $editorId with user map:\n$participants")

    participants.foreach(part => context.watch(part._2))
    context.system.scheduler.scheduleOnce(5.seconds, () => context.self ! CollabRoomEvent.UpdateContent)

    Behaviors.receiveMessage[CollabRoomEvent] {
      case incomingEvent @ CollabRoomEvent.EditorEvent(user, _) =>
        log.trace(s"[collabroom-$editorId] $incomingEvent")
        broadcast(user, incomingEvent, participants)
        persistActor ! AddAction(incomingEvent.event)
        Behaviors.same

      case login @ UserLogin(user, replyTo) =>
        log.trace(s"[collabroom-$editorId] New user login $user")

        context.self ! CollabRoomEvent.ContentRequest(context.self)

        val flow = EditorFlow.getEditorFlow(user, replyTo, context.self)
        replyTo ! CollabFlow(flow)
        log.trace(s"[collabroom-$editorId] User $user was given flow.")
        broadcast(user, login, participants)
        getPersistedContent(editorId, participants, persistActor, sysUser)

      case UserFlowCreated(user, _, chatFlowActor) =>
        val newParticipants = participants + (user -> chatFlowActor)
        log.debug(s"[collabroom-$editorId] new participants map $newParticipants")
        initialized(editorId, newParticipants, persistActor, sysUser)

      case logout @ UserLogout(user) =>
        val newParticipantMap = participants - user
        broadcast(user, logout, newParticipantMap)
        if (newParticipantMap.isEmpty) {
          log.info(s"[collabroom-$editorId] user $user logged out. No other users. Stopping collab room.")
          persistActor ! MakeSnapshot
          Behaviors.stopped
        } else {
          log.info(s"[collabroom-$editorId] user $user logged out.Remaining users $newParticipantMap")
          getParticipantContent(editorId, newParticipantMap, persistActor, sysUser)
        }

      case ping @ CollabRoomEvent.Ping(user, replyTo) =>
        broadcast(user, ping, participants)
        Behaviors.same

      case CollabRoomEvent.ContentRequest(replyTo) =>
        getPersistedContent(editorId, participants, persistActor, sysUser)

      case content: CollabRoomEvent.PersistedContent =>
        log.error(s"[collabroom-$editorId] recvd persisted content in initialized state.")
        throw new RuntimeException(s"[collabroom-$editorId] recvd persisted content in initialized state.")
        Behaviors.stopped
        //  Quill.js hack
        val filteredDeltas = content.deltas.filter {
          case _: Retain => false
          case _ => true
        }
        content.requestor ! CollabRoomEvent.EditorContent(content.content, filteredDeltas, sysUser)
        Behaviors.same

      case CollabRoomEvent.EditorContent(content, deltas, user) =>
        log.error(s"[collabroom-$editorId] recvd editor content in initialized state.")
        throw new RuntimeException(s"[collabroom-$editorId] recvd editor content in initialized state.")
        Behaviors.stopped
        broadcast(sysUser, CollabRoomEvent.EditorContent(content, deltas, sysUser), participants)
        initialized(editorId, participants, persistActor, sysUser)

      case CollabRoomEvent.ClearContent(user) =>
        log.info(s"[collabroom-$editorId] user $user cleared content")
        persistActor ! PersistCommand.ClearContent
        getPersistedContent(editorId, participants, persistActor, sysUser)

      case CollabRoomEvent.CollabActorFailure(msg) =>
        log.error(msg)
        uninitialized(editorId, Some(persistActor))

      case CollabRoomEvent.UpdateContent =>
        log.info(s"[collabroom-$editorId] UpdateContent request.")
        getParticipantContent(editorId, participants, persistActor, sysUser)

    }.receiveSignal {
      case (context, PostStop) =>
        log.error(s"[collabroom-$editorId - signal] stopped.")
        Behaviors.stopped
      case (context, Terminated(deadFlowActor)) =>
        log.error(s"[collabroom-$editorId - signal] flow actor ${deadFlowActor.path} dead.")
        Behaviors.same
      case (_, signal) =>
        log.error(s"[collabroom-$editorId - signal] $signal")
        Behaviors.same
    }
  }

  def getPersistedContent(editorId: Int,
                          participants: HashMap[User, ActorRef[CollabRoomEvent]],
                          persistActor: ActorRef[PersistCommand],
                          sysUser: User,
                 ): Behavior[CollabRoomEvent] = Behaviors.withStash(BUFFER_SIZE) { buffer =>
    Behaviors.setup { context =>

      implicit val parts: Map[User, ActorRef[CollabRoomEvent]] = participants
      context.log.debug("[collabroom-$editorId - getPersistedContent] waiting for content")

      persistActor ! PersistCommand.GetContent(context.self)

      Behaviors.receiveMessage {
        case editorContent @ CollabRoomEvent.EditorContent(content, deltas, user) =>
          context.log.error(s"[collabroom-$editorId - getPersistedContent] recvd editor content when waiting for persisted content.")
          buffer.stash(editorContent)
          Behaviors.same
        case content: CollabRoomEvent.PersistedContent =>
          //  Quill.js hack
          val filteredDeltas = content.deltas.filter {
            case _: TextEditorDomain.Retain => false
            case _ => true
          }
          context.log.info(s"[collabroom-$editorId - getPersistedContent] recvd persisted content. ")
          broadcast(sysUser, CollabRoomEvent.EditorContent(content.content, filteredDeltas, sysUser), participants)
          buffer.unstashAll(initialized(editorId, participants, persistActor, sysUser))
        case CollabRoomEvent.CollabActorFailure(msg) =>
          context.log.error(s"[collabroom-$editorId - getPersistedContent] $msg")
          uninitialized(editorId, Some(persistActor))
        case message =>
          buffer.stash(message)
          Behaviors.same
      }
    }
  }

  def getParticipantContent(editorId: Int,
                            participants: HashMap[User, ActorRef[CollabRoomEvent]],
                            persistActor: ActorRef[PersistCommand],
                            sysUser: User,
                           ): Behavior[CollabRoomEvent] = Behaviors.withStash(BUFFER_SIZE) { buffer =>
    Behaviors.setup { context =>
      import CollabRoomEvent._
      import PersistCommand._

      implicit val timeout: Timeout = 500.millis

      val tokenParticipant = participants.head
      context.log.info(s"[collabroom-$editorId] Asking ${tokenParticipant._1} for content")

      tokenParticipant._2 ! CollabRoomEvent.ContentRequest(context.self)

      context.log.info(s"[collabroom-$editorId] still here")

      Behaviors.receiveMessage {
        case EditorContent(content, deltas, participantProvidingContent) =>
          context.log.info(s"[collabroom-$editorId] got user $participantProvidingContent content: $content")
          persistActor ! SaveContent(content)
          persistActor ! MakeSnapshot
          broadcast(participantProvidingContent, EditorContent(content, deltas, sysUser), participants)
          buffer.unstashAll(initialized(editorId, participants, persistActor, sysUser))
        case CollabActorFailure(msg) =>
          context.log.error(s"[collabroom-$editorId] getParticipantContent: $msg")
          uninitialized(editorId, Some(persistActor))
        case message =>
          buffer.stash(message)
          Behaviors.same
      }
    }
  }

}
