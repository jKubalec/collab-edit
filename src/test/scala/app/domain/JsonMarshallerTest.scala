package app.domain

import spray.json._
import concurentDocs.app.domain.EditorUpdateMessageJsonProtocol
import concurentDocs.app.domain.TextEditorDomain.{DeltaMessage, EditorDelta, EditorMessage, InsertString, Retain, Welcome}
import concurentDocs.app.domain.UserDomain.User
import org.scalatest.flatspec.AnyFlatSpec


class JsonMarshallerTest extends AnyFlatSpec with EditorUpdateMessageJsonProtocol  {


  "Json protocol" should "parse Jsons properly" in {
    val data = "{\"type\":\"delta\",\"delta\":{\"ops\":[{\"retain\":36},{\"insert\":\"f\"}]}}"
    println(data)
    val result = data.parseJson.convertTo[EditorMessage]
    println(result)

    val data2 = "{\"type\":\"delta\",\"delta\":{\"ops\":[{\"retain\":51},{\"insert\":\"bold_text\",\"attributes\":{\"bold\":true}}]}}"
    println(data2.parseJson.convertTo[EditorMessage])
  }

  "Json protocol" should "encode Jsons properly" in {
    val sampleEditorMessage = DeltaMessage(ops = List(Retain(12), InsertString("a")))
    val encoded = sampleEditorMessage.toJson
    println(encoded)
    println(Welcome(User("Honza")).toJson)
  }

}
