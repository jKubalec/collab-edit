package concurentDocs.service.internals

import akka.NotUsed
import akka.actor.typed.ActorRef
import akka.http.scaladsl.model.ws.{Message, TextMessage}
import akka.stream.scaladsl.{Flow, GraphDSL, Keep, Merge, Sink, Source}
import akka.stream.scaladsl.GraphDSL.Implicits._
import akka.stream.typed.scaladsl.{ActorSink, ActorSource}
import akka.stream.{FlowShape, Graph, OverflowStrategy, SourceShape, UniformFanInShape}
import concurentDocs.app.domain.EditorUpdateMessageJsonProtocol
import concurentDocs.app.domain.TextEditorDomain._
import concurentDocs.app.domain.UserDomain.User
import concurentDocs.service.actor.ChatRoomActor.ChatRoomEvent
import concurentDocs.service.actor.ChatRoomActor.ChatRoomEvent._
import spray.json._
/*
object EditorFlow_old extends EditorUpdateMessageJsonProtocol {
  val simpleReturnFlow: GraphDSL.Builder[_] => FlowShape[EditorUpdateMessage, EditorUpdateMessage] = builder => {
    val printMessageFlow = builder.add(Flow[EditorUpdateMessage].map { msg =>
      println(s"Flow processed msg: $msg")
      msg
    })
    FlowShape(printMessageFlow.in, printMessageFlow.out)
  }

  def simpleReturnFlowGraph(): Graph[FlowShape[EditorUpdateMessage, EditorUpdateMessage], NotUsed] = {
    GraphDSL.create() { implicit builder: GraphDSL.Builder[NotUsed] =>
        simpleReturnFlow(builder)
      }
  }

  // TODO:
  def getBufferedFlow() = {
    val bufferSize = 100
    val overflowStrategy = OverflowStrategy.backpressure

    Flow.fromGraph(simpleReturnFlowGraph())
  }


  def chatFlowShape(chatRoomActor: ActorRef[ChatRoomEvent], user: User)(implicit builder: GraphDSL.Builder[_]): FlowShape[Message, Message] = {
    val fromWebsocket: FlowShape[Message, Option[ChatRoomEvent]] = builder.add(
      Flow[Message].collect { //  to be replaced by "EditorDelta" eventually
        case TextMessage.Strict(text) =>
          println(s"[chatFlowShape] incoming message into the flow: $text")
          try {
            Some(EditorEvent(user, text.parseJson.convertTo[EditorUpdateMessage]))
          } catch {
            case ex: Exception =>
              println(s"[chatFlowShape] Message processing fail: $ex")
              None
          }
        case x: Message =>
          println(s"[chatFlowShape] Recv unknown message[Message] $x")
          None

        case unknown =>
          println(s"[chatFlowShape] unknown flow message $unknown")
          None
      })

    val filterIncomingMessages: FlowShape[Option[ChatRoomEvent], ChatRoomEvent] = builder.add(
      Flow[Option[ChatRoomEvent]].collect {
        case Some(msg) => msg
      }
    )

    val initialMessageSourceShape: SourceShape[ChatRoomEvent] = builder.add(Source.single(UserLogin(user, chatRoomActor)))

    val merge: UniformFanInShape[ChatRoomEvent, ChatRoomEvent] = builder.add(Merge[ChatRoomEvent](2))

    val chatActorSink = ActorSink.actorRef[ChatRoomEvent](
      ref = chatRoomActor,
      onCompleteMessage = UserLogout(user),
      onFailureMessage = { t: Throwable => SystemEvent(t.toString) }
    )

    val chatSource: SourceShape[ChatRoomEvent] = builder.add(
      ActorSource.actorRef[ChatRoomEvent](
        completionMatcher = PartialFunction.empty,
        failureMatcher = PartialFunction.empty,
        bufferSize = 100,
//        OverflowStrategy.backpressure //  need to add async boundary
        OverflowStrategy.dropHead
      )
    )

    val backToWebsocket = builder.add(
      Flow[ChatRoomEvent].collect {
        case outgoingEvent: EditorEvent => TextMessage(outgoingEvent.event.toJson.toString())
      })

    fromWebsocket ~> filterIncomingMessages

    filterIncomingMessages ~> chatActorSink

//    filterIncomingMessages ~> merge.in(0)
//    initialMessageSourceShape ~> merge.in(1)

//    merge.out ~> chatActorSink

    chatSource ~> backToWebsocket.in

    FlowShape(fromWebsocket.in, backToWebsocket.out)
  }

  def chatFlow(chatRoomActor: ActorRef[ChatRoomEvent], user: User): Flow[Message, Message, ActorRef[ChatRoomEvent]] = {
    Flow.fromGraph(
      GraphDSL.createGraph(
        ActorSource.actorRef[ChatRoomEvent](
          completionMatcher = PartialFunction.empty,
          failureMatcher = PartialFunction.empty,
          bufferSize = 100,
          OverflowStrategy.dropHead
        )) {
        implicit builder =>
          chatSource => //source provided as argument

            val fromWebsocket: FlowShape[Message, Option[ChatRoomEvent]] = builder.add(
              Flow[Message].collect { //  to be replaced by "EditorDelta" eventually
                case TextMessage.Strict(text) =>
                  println(s"[chatFlowShape] incoming message into the flow: $text")
                  try {
                    Some(EditorEvent(user, text.parseJson.convertTo[EditorUpdateMessage]))
                  } catch {
                    case ex: Exception =>
                      println(s"[chatFlowShape] Message processing fail: $ex")
                      None
                  }
                case x: Message =>
                  println(s"[chatFlowShape] Recv unknown message[Message] $x")
                  None

                case unknown =>
                  println(s"[chatFlowShape] unknown flow message $unknown")
                  None
              })

            val filterIncomingMessages: FlowShape[Option[ChatRoomEvent], ChatRoomEvent] = builder.add(
              Flow[Option[ChatRoomEvent]].collect {
                case Some(msg) => msg
              }
            )

//            val sourceRef = ActorSource.actorRef[ChatRoomEvent](
//              completionMatcher = PartialFunction.empty,
//              failureMatcher = PartialFunction.empty,
//              bufferSize = 10,
//              overflowStrategy = OverflowStrategy.fail
//            )

            val initialMessageSourceShape: SourceShape[ChatRoomEvent] = builder.add(Source.single(UserLogin(user, chatRoomActor)))

            val merge: UniformFanInShape[ChatRoomEvent, ChatRoomEvent] = builder.add(Merge[ChatRoomEvent](2))

//            val initialUserLoginSource: Source[ChatRoomEvent, UserLogin] = sourceRef.mapMaterializedValue(actor => {
//              println(s"[chatFlowShape] User $user joined to $actor")
//              UserLogin(user, actor)
//            })

//            val combinedFlowSource = initialUserLoginSource.concat(Source.asSubscriber.via(fromWebsocket))
//          val websocketEventSource: Source[ChatRoomEvent, _] = Source.asSubscriber[Message]
//            .via(Flow.fromGraph(fromWebsocket))
//            .collect {
//              case Some(editorUpdateMessage) => ChatRoomEvent.EditorEvent(/* user */ , editorUpdateMessage)
// //              You might need to provide a User instance for EditorEvent
//            }
//            val combinedFlowSource = initialUserLoginSource.concat(Source.asSubscriber(fromWebsocket))

//            val combinedSourceShape = builder.add(combinedFlowSource)

            val chatActorSink = ActorSink.actorRef[ChatRoomEvent](
              ref = chatRoomActor,
              onCompleteMessage = UserLogout(user),
              onFailureMessage = { t: Throwable => SystemEvent(t.toString) }
            )

//            val printUnexpectedMessages = builder.add(Sink.foreach[Any](msg => println(s"[chatFlowShape] unknown msg $msg")))

//            val unpackAndFilter: Flow[Option[EditorUpdateMessage], EditorUpdateMessage, NotUsed] = builder.add (
//            val unpackAndFilter: FlowShape[ChatRoomEvent, ChatRoomEvent] = builder.add (
//              Flow[Option[EditorEvent]]
//              .filter(_.isDefined)
//              .map(_.get)
//              .collect {
//                case
//              }
//            )

            val backToWebsocket = builder.add(
              Flow[ChatRoomEvent].collect {
                case outgoingEvent: EditorEvent => TextMessage(outgoingEvent.event.toJson.toString())
              })
              //  TODO: WTF??? How can I print messages?
//                Flow[ChatRoomEvent]
//                  .filterNot(_.isInstanceOf[EditorEvent])
//                  .to(printUnexpectedMessages)// Filter out EditorEvent messages
//                  .toMat(Sink.foreach(msg => println(s"[chatFlowShape] unknown msg $msg")))(Keep.right)
//              )
//            )

            fromWebsocket ~> filterIncomingMessages

            filterIncomingMessages ~> merge.in(0)
            initialMessageSourceShape ~> merge.in(1)

            merge.out ~> chatActorSink

            chatSource ~> backToWebsocket.in

//            unpackAndFilter.out ~> backToWebsocket

            FlowShape(fromWebsocket.in, backToWebsocket.out)
      })
  }

}
*/