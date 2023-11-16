package concurentDocs.service.actor

import akka.actor.typed.SpawnProtocol.Spawn
import akka.actor.typed._
import akka.actor.typed.scaladsl.AskPattern.Askable
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.CacheDirectives._
import akka.http.scaladsl.model.headers._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.server.directives.CachingDirectives.cachingProhibited
import akka.stream.Materializer
import akka.util.Timeout
import concurentDocs.app.domain.EditorUpdateMessageJsonProtocol
import concurentDocs.app.domain.UserDomain.User
import concurentDocs.service.actor.ChatProviderActor.ChatProviderMessage
import concurentDocs.service.actor.ChatProviderActor.ChatProviderMessage._
import org.slf4j.Logger

import java.time.LocalDateTime
import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future}
import scala.io.StdIn
import scala.util.{Failure, Success}

object WebServer extends EditorUpdateMessageJsonProtocol {
//  val noCacheHeaders = List(
//    headers.`Cache-Control`(CacheDirectives.`no-cache`, CacheDirectives.`no-store`, CacheDirectives.`must-revalidate`),
//    headers.`Pragma`(`no-cache`),
//    headers.RawHeader("Expires", "0")
//  )


  def startupRoutes(system: ActorSystem[_], chatProviderActor: ActorRef[ChatProviderMessage])
                   (implicit timeout: Timeout): Route = {
    implicit val scheduler = system.scheduler
    pathPrefix("init") {
      get {
        path(Segment / IntNumber / "ws") { (userName, chatId) =>
          val wsFuture = chatProviderActor.ask(ref => ChatRequest(chatId, User(userName), ref))(timeout, scheduler)
          onSuccess(wsFuture) {
            case ChatFlowResponse(wsFlow) => {
              println(s"Returning WS for user $userName on chat $chatId. Opening socket")
              system.log.info("recvd flow and opening socket.")
              handleWebSocketMessages(wsFlow)
            }
            case a => {
              system.log.error(a.toString)
              println(a.toString)
              complete(StatusCodes.InternalServerError)
            }
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
//          cachingProhibited {
            getFromDirectory("src/main/resources/js")
//          }
        }
      } ~
      pathSingleSlash {
        redirect("/init", StatusCodes.PermanentRedirect)
      }
  }

  def startHttpServer(routes: Route)(implicit system: ActorSystem[SpawnProtocol.Command], ec: ExecutionContext): Future[Http.ServerBinding] = {
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
    //    implicit val system: ActorSystem[Nothing] = ActorSystem[Nothing](Behaviors.empty, "HelloAkkaHttpServer")
    implicit val system: ActorSystem[SpawnProtocol.Command] = ActorSystem(SpawnProtocol(), "StreamWebChat")
    implicit val executionContext: ExecutionContextExecutor = system.executionContext
    implicit val materializer = Materializer(system)

    implicit val timeout: Timeout = Timeout(6.seconds)
    implicit val scheduler: Scheduler = system.scheduler
    implicit val log: Logger = system.log

    val chatProvider = system.ask[ActorRef[ChatProviderMessage]] { ref =>
      Spawn[ChatProviderMessage](
        behavior = ChatProviderActor(),
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
