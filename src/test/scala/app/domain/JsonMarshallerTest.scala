package app.domain

import concurentDocs.app.domain.TextEditorDomain
import concurentDocs.app.json.EditorUpdateMessageJsonProtocol
import org.scalatest.flatspec.AnyFlatSpec
import spray.json._

import scala.collection.immutable.HashMap


class JsonMarshallerTest extends AnyFlatSpec with EditorUpdateMessageJsonProtocol  {


  "Json protocol" should "parse Jsons properly" in {
    import TextEditorDomain._
    val data = "{\"type\":\"delta\",\"delta\":{\"ops\":[{\"retain\":36},{\"insert\":\"f\"}]}}"
    val result = data.parseJson.convertTo[EditorMessage]

    assert(result == DeltaMessage(List(Retain(36), InsertString("f"))))

    val data2 = "{\"type\":\"delta\",\"delta\":{\"ops\":[{\"retain\":51},{\"insert\":\"bold_text\",\"attributes\":{\"bold\":true}}]}}"
    val result2 = data2.parseJson.convertTo[EditorMessage]

    assert(result2 == DeltaMessage(List(Retain(51), InsertStringWithAttributes("bold_text", HashMap("bold" -> AttributeBool(true))))))
  }
}
