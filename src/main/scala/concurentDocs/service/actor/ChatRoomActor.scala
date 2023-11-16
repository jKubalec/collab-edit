package concurentDocs.service.actor

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}
import akka.http.scaladsl.model.ws.Message
import akka.stream.scaladsl.Flow
import concurentDocs.app.domain.TextEditorDomain.{DeltaMessage, EditorUpdateMessage, SystemMessage}
import concurentDocs.app.domain.UserDomain.User
import concurentDocs.service.internals.EditorFlow

import scala.collection.immutable.HashMap

object ChatRoomActor {

  //  case class ChatTuple(actorRef: ActorRef[ChatRoomEvent], flow: Flow[Message, Message, ActorRef[ChatRoomEvent]])
  case class ChatTuple(actorRef: ActorRef[ChatRoomEvent], flow: Flow[Message, Message, _])

  trait ChatRoomEvent

  object ChatRoomEvent {
    case class EditorEvent(user: User, event: DeltaMessage) extends ChatRoomEvent

    case class UserLogout(user: User) extends ChatRoomEvent

    case class UserLogin(user: User, replyTo: ActorRef[ChatRoomEvent]) extends ChatRoomEvent

    case class Ping(user: User, replyTo: ActorRef[ChatRoomEvent]) extends ChatRoomEvent

    case class UserFlowCreated(user: User, requestorActor: ActorRef[ChatRoomEvent], chatFlowActor: ActorRef[ChatRoomEvent]) extends ChatRoomEvent

    //  TODO: this returns WS messages, rewrite to domain only
    //    case class ChatFlow(flow: Flow[Message, Message, ActorRef[ChatRoomEvent]]) extends ChatRoomEvent
    case class ChatFlow(flow: Flow[Message, Message, _]) extends ChatRoomEvent

//    case class SystemEvent(user: User, event: SystemMessage) extends ChatRoomEvent

    case class ChatError(e: Throwable) extends ChatRoomEvent
  }

  def apply(chatId: Int): Behavior[ChatRoomEvent] = uninitialized(chatId)

  def uninitialized(chatId: Int): Behavior[ChatRoomEvent] = Behaviors.setup { context =>
    println(s"starting chat room $chatId")
    context.log.info(s"starting chat room w. chatId $chatId")
    initialized(chatId, HashMap())
  }

  def initialized(chatId: Int, participants: HashMap[User, ActorRef[ChatRoomEvent]]): Behavior[ChatRoomEvent]
  = Behaviors.setup { context =>
    import ChatRoomEvent._
    implicit val log = context.log

    def broadcast(from: User, event: ChatRoomEvent) = participants.filter(_._1 != from).foreach(_._2 ! event)

    println(s"Initializing ChatRoom $chatId with user map:\n$participants")
    context.log.info(s"Initializing ChatRoom $chatId with user map:\n$participants")
    Behaviors.receiveMessage {
      case incomingEvent @ EditorEvent(user, _) => {
        println(s"[chat-$chatId] $incomingEvent")
        broadcast(user, incomingEvent)
        Behaviors.same
      }
      case login @ UserLogin(user, replyTo) => {
        //        val newParticipantMap = participants + (user -> userFlowActor)
        context.log.info(s"New user login $user")
        println(s"New user login $user")
        val flow = EditorFlow.getEditorFlow(user, replyTo, context.self)
        replyTo ! ChatFlow(flow)
        broadcast(user, login)
        println(s"User $user was given flow.")
        //        initialized(chatId, newParticipantMap)
        Behaviors.same
      }
      case UserFlowCreated(user, requestorActor, chatFlowActor) => {
        val newParticipants = participants + (user -> chatFlowActor)
        println(s"new participants map $newParticipants")
        initialized(chatId, newParticipants)
      }
      case logout @ UserLogout(user) => {
        val newParticipantMap = participants - user
        broadcast(user, logout)
        context.log.info(s"user $user logged out")
        initialized(chatId, newParticipantMap)
      }
      case ping @ Ping(user, replyTo) => {
//        broadcast(user, ping)
        Behaviors.same
      }
      /*
      case ChatFlowRequest(user, replyTo) => {
        println(s"Recieved chat flow request from $user")
        participants.get(user) match {
          case Some(chatTuple) => {
            println(s"found a flow already")
            replyTo ! ChatFlow(chatTuple._2)
            Behaviors.same
          }
          case None => {
            println(s"creating new flow")
//            val flow = EditorFlow.chatFlow(context.self, user)
            val graph = GraphDSL.create() { implicit builder: GraphDSL.Builder[NotUsed] =>
              println(s"builder $builder ")
              val shape = EditorFlow_old.chatFlowShape(context.self, user)
              println(s"graph shape $shape")
              shape
            }
            println(s"graph created $graph")
            val flow = Flow.fromGraph(graph)
            println(s"flow created $flow")
            val newParticipants = participants + (user -> (context.self, flow))
            println(s"new participants set $newParticipants")
            replyTo ! ChatFlow(flow)
            println(s"sending back the flow to $replyTo")
            initialized(chatId, newParticipants)
          }
        }

       */
      //        if (participants.keySet.contains(user)) {
      //          replyTo ! ChatFlow
      //        }
      //        val flow = EditorFlow.chatFlowShape(context.self, user)
      //        replyTo ! ChatFlow(flow)
      //        Behaviors.same
    }
  }
}
