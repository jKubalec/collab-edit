package concurentDocs.service.actor

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}
import akka.http.scaladsl.model.ws.Message
import akka.stream.scaladsl.Flow
import akka.util.Timeout
import concurentDocs.app.domain.UserDomain.User
import concurentDocs.service.actor.ChatRoomActor.ChatRoomEvent

import scala.concurrent.duration.DurationInt
import scala.util.Success

object ChatProviderActor {

  trait ChatProviderMessage
  object ChatProviderMessage {
    case class ChatRequest(chatId: Int, user: User, replyTo: ActorRef[ChatProviderMessage]) extends ChatProviderMessage
    case class ChatFlowProvided(flow: Flow[Message, Message, _], replyTo: ActorRef[ChatProviderMessage]) extends ChatProviderMessage
    case class ChatFlowResponse(flow: Flow[Message, Message, _]) extends ChatProviderMessage
  }

  case class ChatRoom(id: Int, chatRoomActor: ActorRef[ChatRoomEvent])

  def apply(): Behavior[ChatProviderMessage] = uninitialized()

  def uninitialized(): Behavior[ChatProviderMessage] = Behaviors.setup { context =>
    println("starting chat provider")
    context.log.info("starting chat provider")
    initialized(List())
  }

  def initialized(chats: List[ChatRoom]): Behavior[ChatProviderMessage] = Behaviors.receive[ChatProviderMessage] { (context, message) =>
    import ChatProviderMessage._
    import ChatRoomEvent._
    implicit val timeout: Timeout = 3.seconds

    println(s"Chat provider running with chats $chats")
    context.log.info(s"Chat provider running with chats $chats")

    message match {
      case ChatRequest(chatId, user, replyTo) => {
        println(s"User $user requesting a chat $chatId")
        context.log.info(s"User $user requesting a chat $chatId")
        val myChatOpt = chats.find(_.id == chatId)
        myChatOpt match {
          case Some(myChat) => {
            println(s"chat found $myChat")
//            if (myChat.users.contains(user)) {
              context.ask(myChat.chatRoomActor, ref => UserLogin(user, ref)) {
                  case Success(msg) => msg match {
                    case ChatFlow(flow) => ChatFlowProvided(flow, replyTo)
                  }
//                  case Failure(exception) => context.log.error(exception.toString())
                }
              Behaviors.same
//            } else {
//              val newChat = myChat.addUser(user)
//              newChat ! UserLogin(user, )
//              replyTo ! ChatStream(newChat.flow)
//              println(s"Chat found, adding user: $newChat")
//              context.log.info(s"Chat found, adding user: $newChat")
//              initialized(chats.filter(_.id != chatId) :+ newChat)
//            }
          }
          case None => {
            println("chat not found")
            val newChatRoom = context.spawn(ChatRoomActor(chatId), s"chat-room-$chatId")
            val newChatList = chats :+ ChatRoom(chatId, newChatRoom)
            println(s"Spawned new chat room $newChatRoom")
            context.ask(newChatRoom, ref => UserLogin(user, ref)) {
                case Success(msg) => {
                  println(s"Chatroom responded to request for chat $chatId: $msg")
                  msg match {
                    case ChatFlow(flow) => ChatFlowProvided(flow, replyTo)
                  }
                }
              //                  case Failure(exception) => context.log.error(exception.toString())
            }
            initialized(newChatList)
//            println(newChatId)
//            val newChat = ChatRoom(newChatId, Set(user), WebSocketFlowWrapper.simpleReturnFlow(EditorFlow.simpleReturnFlow))
//            println(newChat)
//            replyTo ! ChatStream(newChat.flow)
//            println(s"Chat not found, adding new chat: $newChat")
//            context.log.info(s"Chat not found, adding new chat: $newChat")
//            initialized(chats :+ newChat)
          }
        }
      }
      case ChatFlowProvided(flow, replyTo) => {
        println(s"Recieved chat flow $flow, sending to $replyTo")
        replyTo ! ChatFlowResponse(flow)
        Behaviors.same
      }
    }
  }



}
