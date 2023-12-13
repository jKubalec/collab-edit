package concurentDocs.service.actor

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.Terminated
import akka.http.scaladsl.model.ws.Message
import akka.stream.scaladsl.Flow
import akka.util.Timeout
import concurentDocs.app.domain.TextEditorDomain.FrontendMessage
import concurentDocs.app.domain.UserDomain.User
import concurentDocs.service.actor.CollabRoomActor.CollabRoomEvent

import scala.concurrent.duration.DurationInt
import scala.util.Success

object CollabRoomProviderActor {

  sealed trait CollabRoomProviderMessage

  object CollabRoomProviderMessage {

    case class CollabRoomRequest(chatId: Int, user: User, replyTo: ActorRef[CollabRoomProviderMessage])
        extends CollabRoomProviderMessage

    case class CollabFlowProvided(
        user: User,
        flow: Flow[FrontendMessage, FrontendMessage, _],
        replyTo: ActorRef[CollabRoomProviderMessage]
    ) extends CollabRoomProviderMessage

    case class CollabFlowResponse(user: User, flow: Flow[FrontendMessage, FrontendMessage, _])
        extends CollabRoomProviderMessage

    case class CollabRoomFinished(chatId: Int, collabRoomRef: ActorRef[CollabRoomEvent])
        extends CollabRoomProviderMessage

  }

  case class CollabRoom(id: Int, chatRoomActor: ActorRef[CollabRoomEvent])

  def apply(): Behavior[CollabRoomProviderMessage] = uninitialized()

  def uninitialized(): Behavior[CollabRoomProviderMessage] = Behaviors.setup { context =>
    initialized(List())
  }

  def initialized(chats: List[CollabRoom]): Behavior[CollabRoomProviderMessage] = Behaviors
    .receive[CollabRoomProviderMessage] { (context, message) =>
      import CollabRoomProviderMessage._
      import CollabRoomEvent._
      implicit val timeout: Timeout = 3.seconds

      context.log.debug(s"Chat provider running with chats $chats")

      message match {
        case CollabRoomRequest(chatId, user, replyTo) =>
          context.log.trace(s"User $user requesting a chat $chatId")
          val myChatOpt = chats.find(_.id == chatId)

          myChatOpt match {
            case Some(myChat) =>
              context.ask(myChat.chatRoomActor, ref => UserLogin(user, ref)) { case Success(msg) =>
                msg match {
                  case CollabFlow(flow) => CollabFlowProvided(user, flow, replyTo)
                }
              }
              Behaviors.same
            case None =>
              val newChatRoom = context.spawn(CollabRoomActor(chatId), s"chat-room-$chatId")
              context.watch(newChatRoom)
              val newChatList = chats :+ CollabRoom(chatId, newChatRoom)
              context.log.trace(s"Spawning new chat room $newChatRoom")
              context.ask(newChatRoom, ref => UserLogin(user, ref)) { case Success(msg) =>
                context.log.trace(s"Chatroom responded to request for chat $chatId: $msg")
                msg match {
                  case CollabFlow(flow) => CollabFlowProvided(user, flow, replyTo)
                }
              }
              initialized(newChatList)
          }

        case CollabFlowProvided(user, flow, replyTo) =>
          context.log.trace(s"Recieved chat flow $flow, sending to $replyTo")
          replyTo ! CollabFlowResponse(user, flow)
          Behaviors.same
        case m @ CollabRoomFinished(_, actorRef) =>
          initialized(chats.filterNot(_.chatRoomActor == actorRef))
      }
    }
    .receiveSignal {
      //  TODO: check
      case (context, Terminated(deadCollabRoom)) =>
        val remainingChats = chats.filterNot(_.chatRoomActor == deadCollabRoom)
        context.log.info(s"[CollabRoomProvider - signal] Collab room ${deadCollabRoom.path.name} finished. Remaining chats: $remainingChats")
        initialized(remainingChats)
    }

}
