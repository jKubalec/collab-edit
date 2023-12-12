package concurentDocs.service.internals

import akka.NotUsed
import akka.actor.typed.ActorRef
import akka.stream._
import akka.stream.scaladsl.{Flow, GraphDSL, Merge, Sink, Source}
import akka.stream.typed.scaladsl.{ActorSink, ActorSource}
import concurentDocs.app.domain.TextEditorDomain
import concurentDocs.app.domain.TextEditorDomain._
import concurentDocs.app.domain.UserDomain.User
import concurentDocs.service.actor.CollabRoomActor.CollabRoomEvent
import concurentDocs.service.actor.CollabRoomActor.CollabRoomEvent.{CollabError, UserLogout}
import org.slf4j.Logger

object EditorFlow {

  def getEditorFlow(user: User, flowRequestorActor: ActorRef[CollabRoomEvent], chatRoomActor: ActorRef[CollabRoomEvent])
                   (implicit log: Logger): Flow[FrontendMessage, FrontendMessage, _] = {

    val completionMatcher: PartialFunction[CollabRoomEvent, Unit] = {
      case UserLogout(user) => log.info(s"[EditorFlow - $user] Completing flow with user logout") // Define the message that should complete the stream
    }

    val failureMatcher: PartialFunction[CollabRoomEvent, Throwable] = {
      case CollabError(e) => new RuntimeException(e.toString) // Define the message that should fail the stream
    }

    Flow.fromGraph(GraphDSL.createGraph(ActorSource.actorRef[CollabRoomEvent](
      bufferSize = 100,
      overflowStrategy = OverflowStrategy.dropHead, //  backpressure needs async border, better ActorSource.actorRefWithBackPressure
      completionMatcher = completionMatcher,
      failureMatcher = failureMatcher,
    )) { implicit builder =>
      editorSource: SourceShape[CollabRoomEvent] =>
        import CollabRoomEvent._
        import GraphDSL.Implicits._

        var selfActor: ActorRef[CollabRoomEvent] = null

        val flowActorAsSource = builder.materializedValue.map(actor => {
          selfActor = actor
          UserFlowCreated(user, flowRequestorActor, actor)
        })//.withAttributes(ActorAttributes.withSupervisionStrategy(Supervision.stoppingDecider)) //superVisionDecider))

        val fromEditor: FlowShape[FrontendMessage, CollabRoomEvent] = builder.add(
          Flow[FrontendMessage].collect {
            case delta: DeltaMessage => EditorEvent(user, delta)
            case TextEditorDomain.Login(loginUser) => CollabRoomEvent.UserLogin(user, selfActor)
            case TextEditorDomain.Logout => CollabRoomEvent.UserLogout(user)
            case TextEditorDomain.Ping => CollabRoomEvent.Ping(user, selfActor)
            case TextEditorDomain.ContentRequest => CollabRoomEvent.ContentRequest(selfActor)
            case TextEditorDomain.ClearContent => CollabRoomEvent.ClearContent(user)
            case msg@TextEditorDomain.EditorContent(content, deltas) =>
              log.debug(s"[EditorFlow - $user] recvd Editor content $msg")
              CollabRoomEvent.EditorContent(content, deltas.ops, user)
          }
        )

        val backToEditor: FlowShape[CollabRoomEvent, FrontendMessage] = builder.add(
          Flow[CollabRoomEvent].collect {
              case outgoingEvent: EditorEvent => outgoingEvent.event
              case UserLogout(_) => TextEditorDomain.Logout
              case UserLogin(user, _) => TextEditorDomain.Login(user)
              case Ping(_, _) => TextEditorDomain.Ping
              case ContentRequest(_) =>
                log.debug(s"[Editor flow - $user] - asking user $user for content")
                TextEditorDomain.ContentRequest
              case EditorContent(content, deltas, _) => TextEditorDomain.EditorContent(content, DeltaMessage(deltas))
            }
            .async
        )

        val chatActorSink: Sink[CollabRoomEvent, NotUsed] = ActorSink.actorRef(
          ref = chatRoomActor,
          onCompleteMessage = {
            log.info(s"[EditorFlow - $user] flow completed.")
            CollabRoomEvent.UserLogout(user)
          },
          onFailureMessage = throwable => {
            log.error(s"[EditorFlow - $user] ${throwable.toString}")
            CollabRoomEvent.UserLogout(user)
          })


        val greeterSource: Source[FrontendMessage, _] = Source.single(Welcome(user))

        val mergeFromWs = builder.add(Merge[CollabRoomEvent](2))
        val mergeBackToWs = builder.add(Merge[FrontendMessage](2))
        // hack to enable async between backToWebSocket and Merge
        val asyncBoundary: FlowShape[FrontendMessage, FrontendMessage] = builder.add(
          Flow[FrontendMessage].map(x => x)
        )

        fromEditor.map { x =>
          //        log.debug(s"[EditorFlow - $user] from editor recvd: $x")
          x
        } ~> mergeFromWs.in(0)
        flowActorAsSource ~> mergeFromWs.in(1)

        mergeFromWs ~> chatActorSink

        editorSource ~> backToEditor ~> asyncBoundary

        asyncBoundary ~> mergeBackToWs.in(0)
        greeterSource ~> mergeBackToWs.in(1)

        FlowShape[FrontendMessage, FrontendMessage](fromEditor.in, mergeBackToWs.out)

    })
  }

}
