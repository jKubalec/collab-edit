package concurentDocs.service.internals

import akka.NotUsed
import akka.http.scaladsl.model.ws.{Message, TextMessage}
import akka.stream.FlowShape
import akka.stream.scaladsl.{Flow, GraphDSL, Source}
import concurentDocs.app.domain.EditorUpdateMessageJsonProtocol
import concurentDocs.app.domain.TextEditorDomain.{DeltaMessage, EditorDelta, EditorUpdateMessage}
import spray.json._

object WebSocketFlowWrapper extends EditorUpdateMessageJsonProtocol {

  def simpleReturnFlow(flowShapeBuilder: GraphDSL.Builder[_] => FlowShape[EditorUpdateMessage, EditorUpdateMessage]): Flow[Message, Message, _] = {
    Flow.fromGraph(
      GraphDSL.create() { implicit builder: GraphDSL.Builder[NotUsed] =>
        import GraphDSL.Implicits._

        val fromWebsocketShape: FlowShape[Message, Option[DeltaMessage]] = builder.add(
          Flow[Message].collect { //  to be replaced by "EditorDelta" eventually
            case TextMessage.Strict(text) =>
              println(s"incoming message into the flow: $text")
              try {
                Some(text.parseJson.convertTo[DeltaMessage])
              } catch {
                case ex: Exception =>
                  println(s"Message processing fail: $ex")
                  None
              }
            case x: Message =>
              println(s"Recv message[Message] $x")
              None

            case unknown =>
              println(s"[SimpleReturnFlow] unknown flow message $unknown")
              None
          })

        val upackAndFilter: Flow[Option[DeltaMessage], DeltaMessage, NotUsed] = Flow[Option[DeltaMessage]]
          .filter(_.isDefined)
          .map(_.get)

        val unpackAndFilterShape = builder.add(upackAndFilter)

        val backToWebsocketShape = builder.add(
          Flow[DeltaMessage].map {
            case x: DeltaMessage => TextMessage(x.toJson.toString())
          }
        )

        fromWebsocketShape.out ~> unpackAndFilterShape.in
        unpackAndFilterShape.out ~> backToWebsocketShape.in

        FlowShape(fromWebsocketShape.in, backToWebsocketShape.out)
      }
    )
  }
}
