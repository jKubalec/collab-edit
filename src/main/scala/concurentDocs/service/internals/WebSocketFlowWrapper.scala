package concurentDocs.service.internals

import akka.http.scaladsl.model.ws.{Message, TextMessage}
import akka.stream.FlowShape
import akka.stream.scaladsl.{Flow, GraphDSL}
import concurentDocs.app.domain.TextEditorDomain.EditorMessage
import concurentDocs.app.domain.UserDomain.User
import concurentDocs.app.json.EditorUpdateMessageJsonProtocol
import org.slf4j.Logger
import spray.json._

object WebSocketFlowWrapper extends EditorUpdateMessageJsonProtocol {

  def flowWebSocketAdapter(user: User, flow: Flow[EditorMessage, EditorMessage, _])
                          (implicit log: Logger): Flow[Message, Message, _] = {
    Flow.fromGraph(GraphDSL.create() { implicit builder =>
      import GraphDSL.Implicits._

      val fromWebsocket: FlowShape[Message, EditorMessage] = builder.add(
        Flow[Message].collect {
          case TextMessage.Strict(content) => content.parseJson.convertTo[EditorMessage]
        }
      )

      val backToWebsocket: FlowShape[EditorMessage, Message] = builder.add(
        Flow[EditorMessage].map(msg => TextMessage.Strict(editorMessageFormat.write(msg).toString()))
      )

      fromWebsocket ~> flow ~> backToWebsocket

      FlowShape[Message, Message](fromWebsocket.in, backToWebsocket.out)
    })
  }
}
