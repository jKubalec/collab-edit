package concurentDocs.service.actor

import akka.actor.typed.SpawnProtocol.Spawn
import akka.actor.typed._
import akka.actor.typed.scaladsl.AskPattern.Askable
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.Materializer
import akka.util.Timeout
import concurentDocs.app.domain.UserDomain.User
import concurentDocs.app.json.EditorUpdateMessageJsonProtocol
import concurentDocs.service.actor.CollabRoomProviderActor.CollabRoomProviderMessage
import concurentDocs.service.actor.CollabRoomProviderActor.CollabRoomProviderMessage._
import concurentDocs.service.internals.WebSocketFlowWrapper
import org.slf4j.Logger

import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future}
import scala.io.StdIn
import scala.util.{Failure, Success}

object WebServer extends EditorUpdateMessageJsonProtocol {

  def startupRoutes(system: ActorSystem[_], chatProviderActor: ActorRef[CollabRoomProviderMessage])
                   (implicit timeout: Timeout, log: Logger): Route = {
    implicit val scheduler: Scheduler = system.scheduler
    pathPrefix("init") {
      get {
        path(Segment / IntNumber / "ws") { (userName, chatId) =>
          val wsFuture = chatProviderActor.ask(ref => CollabRoomRequest(chatId, User(userName), ref))(timeout, scheduler)
          onSuccess(wsFuture) {
            case CollabFlowResponse(user, wsFlow) =>
              println(s"Returning WS for user $userName on chat $chatId. Opening socket")
              system.log.info("recvd flow and opening socket.")
              handleWebSocketMessages(WebSocketFlowWrapper.flowWebSocketAdapter(user, wsFlow))
            case err =>
              system.log.error(err.toString)
              println(err.toString)
              complete(StatusCodes.InternalServerError)
          }
        } ~
        path(Segment / IntNumber) { (userName, chatId) =>
          println(s"Asking for chat $chatId for user $userName.")
          getFromFile("src/main/resources/html/text_editor.html")
        } ~
        pathEndOrSingleSlash {
          getFromFile("src/main/resources/html/text_editor.html")
        }
      }
    } ~
      pathPrefix("static") {
        get {
            getFromDirectory("src/main/resources/js")
        }
      } ~
      pathSingleSlash {
        redirect("/init", StatusCodes.PermanentRedirect)
      }
  }

  def startHttpServer(routes: Route)
                     (implicit system: ActorSystem[SpawnProtocol.Command], ec: ExecutionContext): Future[Http.ServerBinding] = {
    val host = "localhost"
    val port = 8080

    val futureBinding = Http().newServerAt(host, port).bind(routes)
    futureBinding.foreach(binding =>
      println(s"Server online at http://${binding.localAddress.getHostString}:${binding.localAddress.getPort}/")
    )
    StdIn.readLine()
    futureBinding
  }

  def stopServer(futureBinding: Future[Http.ServerBinding])(implicit system: ActorSystem[_], ec: ExecutionContext): Unit = {
    futureBinding.flatMap(_.unbind()).onComplete(_ => system.terminate())
  }

  def start(): Unit = {
    implicit val system: ActorSystem[SpawnProtocol.Command] = ActorSystem(SpawnProtocol(), "StreamWebCollaboration")
    implicit val executionContext: ExecutionContextExecutor = system.executionContext
    implicit val materializer: Materializer = Materializer(system)

    implicit val timeout: Timeout = Timeout(6.seconds)
    implicit val scheduler: Scheduler = system.scheduler
    implicit val log: Logger = system.log

    val chatProvider = system.ask[ActorRef[CollabRoomProviderMessage]] { ref =>
      Spawn[CollabRoomProviderMessage](
        behavior = CollabRoomProviderActor(),
        name = "",
        props = Props.empty,
        replyTo = ref
      )
    }(timeout, scheduler)

    chatProvider.onComplete {
      case Success(provider) => stopServer(startHttpServer(startupRoutes(system, provider)))
      case Failure(e) => log.error(s"Failed to get Chat Provider actor: $e")
    }
  }
}
