package concurentDocs.service.internals

import akka.http.scaladsl.model.ws.{Message, TextMessage}
import akka.stream.FlowShape
import akka.stream.scaladsl.{Flow, GraphDSL}
import concurentDocs.app.domain.TextEditorDomain
import concurentDocs.app.domain.UserDomain.User
import concurentDocs.app.json.JsonProtocol
import io.circe.Printer
import io.circe.parser._
import io.circe.syntax.EncoderOps
import org.slf4j.Logger

object WebSocketFlowWrapper {
  import JsonProtocol._
  import TextEditorDomain._

  def flowWebSocketAdapter(user: User, flow: Flow[FrontendMessage, FrontendMessage, _])
                          (implicit log: Logger): Flow[Message, Message, _] = {
    Flow.fromGraph(GraphDSL.create() { implicit builder =>
      import GraphDSL.Implicits._

      val fromWebsocket: FlowShape[Message, FrontendMessage] = builder.add(
        Flow[Message].collect {
          case TextMessage.Strict(content) =>
            decode[FrontendMessage](content) match {
              case Right(msg) => msg
              case Left(err) =>
               log.error(s"WS wrapper: decoding $content => ${err.toString}")
               Ping
            }
        }
      )

      val backToWebsocket: FlowShape[FrontendMessage, Message] = builder.add(
        Flow[FrontendMessage].map(msg => TextMessage.Strict(Printer.noSpaces.print(msg.asJson)))
      )

      fromWebsocket ~> flow ~> backToWebsocket

      FlowShape[Message, Message](fromWebsocket.in, backToWebsocket.out)
    })
  }
}
