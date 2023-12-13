package concurentDocs.service.actor

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.PostStop
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.Effect
import akka.persistence.typed.scaladsl.EventSourcedBehavior
import akka.persistence.typed.scaladsl.RetentionCriteria
import concurentDocs.app.domain.TextEditorDomain.DeltaMessage
import concurentDocs.app.domain.TextEditorDomain.EditorDelta
import concurentDocs.service.actor.CollabRoomActor.CollabRoomEvent
import concurentDocs.service.actor.CollabRoomPersistentActor.PersistEvent.SnapshotEvent
import org.slf4j.Logger

object CollabRoomPersistentActor {

  sealed trait PersistCommand

  object PersistCommand {

    case class AddAction(delta: DeltaMessage) extends PersistCommand

    case class GetContent(replyTo: ActorRef[CollabRoomEvent]) extends PersistCommand

    case class Ping(replyTo: ActorRef[CollabRoomEvent]) extends PersistCommand

    case object MakeSnapshot extends PersistCommand

    case object ClearContent extends PersistCommand

    case class SaveContent(content: String) extends PersistCommand

  }

  final case class PersistState(contents: String, deltaHistory: Vector[EditorDelta]) // , snapshotId: Long)

  val persistCommandHandler: (Int, Logger) => (PersistState, PersistCommand) => Effect[PersistEvent, PersistState] = {
    import CollabRoomEvent._
    import PersistEvent._
    (roomId, log) => { (state, command) =>
      command match {
        case PersistCommand.AddAction(delta) => Effect.persist(ActionAdded(delta))
        case PersistCommand.GetContent(replyTo) =>
          replyTo ! PersistedContent(state.contents, state.deltaHistory.toList, replyTo)
          Effect.none
        case PersistCommand.MakeSnapshot         => Effect.persist(SnapshotEvent)
        case PersistCommand.ClearContent         => Effect.persist(Cleared)
        case PersistCommand.SaveContent(content) => Effect.persist(ContentUpdated(content))
        case PersistCommand.Ping(replyTo) =>
          replyTo ! CollabRoomEvent.PersistenceAlive
          Effect.none
      }
    }
  }

  sealed trait PersistEvent

  object PersistEvent {

    case class ActionAdded(delta: DeltaMessage) extends PersistEvent

    case object SnapshotEvent extends PersistEvent

    case object Cleared extends PersistEvent

    case class ContentUpdated(content: String) extends PersistEvent

  }

  val persistEventHandler: (PersistState, PersistEvent) => PersistState = {
    import PersistEvent._
    (state, event) =>
      event match {
        case ActionAdded(delta)      => state.copy(deltaHistory = state.deltaHistory ++ delta.ops)
        case Cleared                 => PersistState("", Vector())
        case ContentUpdated(content) => PersistState(content, Vector())
        case SnapshotEvent           => state
      }
  }

  def apply(roomId: Int): Behavior[PersistCommand] = Behaviors.setup { context =>
    context.log.info(s"Starting persistence actor for $roomId")
    EventSourcedBehavior[PersistCommand, PersistEvent, PersistState](
      persistenceId = PersistenceId.ofUniqueId(s"collabroom-$roomId"),
      emptyState = PersistState("", Vector()),
      commandHandler = persistCommandHandler(roomId, context.log),
      eventHandler = persistEventHandler
    ).snapshotWhen {
      case (_, SnapshotEvent, _) => true
      case (_, _, _)             => false
    }
      .withRetention(
        RetentionCriteria.snapshotEvery(numberOfEvents = 20, keepNSnapshots = 2).withDeleteEventsOnSnapshot
      )
      .receiveSignal { case (state, PostStop) =>
        context.log.warn(s"[persistence for $roomId] actor stopped.")
        Behaviors.same
      }
  }

}
