package concurentDocs.service.internals

import akka.NotUsed
import akka.actor.typed.ActorRef
import akka.http.scaladsl.model.ws.{Message, TextMessage}
import akka.stream.scaladsl.{Flow, GraphDSL, Merge, Sink, Source}
import akka.stream.typed.scaladsl.{ActorSink, ActorSource}
import akka.stream.{FlowShape, OverflowStrategy}
import concurentDocs.app.domain.TextEditorDomain._
import concurentDocs.app.domain.UserDomain.User
import concurentDocs.app.domain.{EditorUpdateMessageJsonProtocol, TextEditorDomain}
import concurentDocs.service.actor.ChatRoomActor.ChatRoomEvent
import concurentDocs.service.actor.ChatRoomActor.ChatRoomEvent.{ChatError, UserLogout}
import org.slf4j.Logger
import spray.json._

object EditorFlow extends EditorUpdateMessageJsonProtocol{

  def getEditorFlow(user: User, flowRequestorActor: ActorRef[ChatRoomEvent], chatRoomActor: ActorRef[ChatRoomEvent])
                   (implicit log: Logger): Flow[Message, Message, _] = {

    val completionMatcher: PartialFunction[ChatRoomEvent, Unit] = {
      case UserLogout(user) => log.info(s"[flow for $user] Completing flow with user logout") // Define the message that should complete the stream
    }

    val failureMatcher: PartialFunction[ChatRoomEvent, Throwable] = {
      case ChatError(e) => new RuntimeException(e.toString) // Define the message that should fail the stream
    }

    Flow.fromGraph(GraphDSL.createGraph(ActorSource.actorRef[ChatRoomEvent](
      bufferSize = 100,
      overflowStrategy = OverflowStrategy.fail,     //  backpressure needs async border, better ActorSource.actorRefWithBackPressure
      completionMatcher = completionMatcher,
      failureMatcher = failureMatcher,
    )){ implicit builder =>
          chatSource =>
            import ChatRoomEvent._
            import GraphDSL.Implicits._

            var selfActor: ActorRef[ChatRoomEvent] = null

            val flowActorAsSource = builder.materializedValue.map(actor => {
              selfActor = actor
              UserFlowCreated(user, flowRequestorActor, actor)
            })

            val fromWebsocket: FlowShape[Message, ChatRoomEvent] = builder.add(
              Flow[Message].collect { msg =>
                println(s"[flow $user] received $msg")
                msg match {
                  case TextMessage.Strict(content) => {
                    println(s"[flow $user] TextMessage.Strict: $content")
                    val editorMessage = content.parseJson.convertTo[EditorMessage]
                    println(s"[flow $user] parsed: $editorMessage")
                    val res = editorMessage match {
                      case delta : DeltaMessage => EditorEvent(user, delta)
                      case TextEditorDomain.Login(user) => ChatRoomEvent.UserLogin(user, selfActor)
                      case TextEditorDomain.Logout(user) => ChatRoomEvent.UserLogout(user)
                      case TextEditorDomain.Ping(user) => ChatRoomEvent.Ping(user, selfActor)
                    }
                    println(s"[flow $user] converted to $res")
                    res
                  }
                }
              }
            )

      val backToWebsocket: FlowShape[ChatRoomEvent, Message] = builder.add(
        Flow[ChatRoomEvent].collect {
          case outgoingEvent: EditorEvent => TextMessage.Strict(editorMessageFormat.write(outgoingEvent.event).toString())
          case UserLogout(user) => TextMessage.Strict(editorMessageFormat.write(TextEditorDomain.Logout(user)).toString())
          case UserLogin(user, _) => TextMessage.Strict(editorMessageFormat.write(TextEditorDomain.Login(user)).toString())
          case Ping(user, _) => TextMessage.Strict(editorMessageFormat.write(TextEditorDomain.Ping(user)).toString())
        }
      )

      val chatActorSink: Sink[ChatRoomEvent, NotUsed] = ActorSink.actorRef(
        ref = chatRoomActor,
        onCompleteMessage = ChatRoomEvent.UserLogout(user),
        onFailureMessage = throwable => {
          log.error(s"[EditorFlow - $user] ${throwable.toString}")
          ChatRoomEvent.UserLogout(user)
        })


      val greeterSource: Source[Message, _] = Source.single(
        TextMessage.Strict(systemMessageFormat.write(Welcome(user)).toString())
      )

      val mergeFromWs = builder.add(Merge[ChatRoomEvent](2))
      val mergeBackToWs = builder.add(Merge[Message](2))

      fromWebsocket.async ~> mergeFromWs.in(0)
      flowActorAsSource ~> mergeFromWs.in(1)

      mergeFromWs ~> chatActorSink

      chatSource ~> backToWebsocket.async

      backToWebsocket ~> mergeBackToWs.in(0)
      greeterSource ~> mergeBackToWs.in(1)

      FlowShape[Message, Message](fromWebsocket.in, mergeBackToWs.out)

    })
  }

}
