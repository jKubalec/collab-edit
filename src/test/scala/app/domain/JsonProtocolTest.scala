package app.domain

import concurentDocs.app.domain.TextEditorDomain
import concurentDocs.app.domain.UserDomain.User
import io.circe.Printer
import io.circe.parser._
import io.circe.syntax.EncoderOps
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import concurentDocs.app.json.JsonProtocol

class JsonProtocolTest extends AnyWordSpecLike with Matchers {
  import SampleJsonMessages._
  import TextEditorDomain._
  import JsonProtocol._

  "System messages json decoder" should {
    "parse Ping" in {
      val result = decode[FrontendMessage](pingMessage)

      result shouldBe Right(Ping)
    }

    "parse Login(user)" in {
      val result = decode[FrontendMessage](loginMessage)

      result shouldBe Right(Login(User(userName)))
    }
  }

  "System messages json encoder" should {
    "encode Ping" in {
      val result = (Ping: FrontendMessage).asJson

      val compactResult = Printer.noSpaces.print(result)
      assert(compactResult == pingMessage)
    }

    "encode Login(user)" in {
      val result = (Login(User(userName)): FrontendMessage).asJson
      val compactResult = Printer.noSpaces.print(result)

      assert(compactResult == loginMessage)
    }
  }

  "Editor messages json decoder" should {
    "parse insert" in {
      val result = decode[FrontendMessage](deltaInsertMessage)

      result shouldBe Right(deltaInsert)
    }

    "parse retain" in {
      val result = decode[FrontendMessage](deltaRetainMessage)

      result shouldBe Right(deltaRetain)
    }

    "parse combined delta message" in {
      val result = decode[FrontendMessage](combinedDeltaMessage)

      result shouldBe Right(combinedDelta)
    }

    "parse debug test" in {
      val result = decode[FrontendMessage](mm2Msg)

      result shouldBe Right(mm2)
    }
  }

  "Editor messages json encoder" should {
    "encode insert" in {
      val result = (deltaInsert: FrontendMessage).asJson
      val compactResult = Printer.noSpaces.print(result)

      assert(compactResult == deltaInsertMessage)
    }

    "encode retain" in {
      val result = (deltaRetain: FrontendMessage).asJson
      val compactResult = Printer.noSpaces.print(result)

      assert(compactResult == deltaRetainMessage)
    }

    "encode combined delta" in {
      val result = (combinedDelta: FrontendMessage).asJson
      val compactResult = Printer.noSpaces.print(result)

      assert(compactResult == combinedDeltaMessage)
    }
  }
}

object SampleJsonMessages {
  import TextEditorDomain._

  val pingMessage = "{\"type\":\"Ping\"}"

  val userName = "Alice"
  val loginMessage = s"{\"type\":\"Login\",\"user\":{\"name\":\"$userName\"}}"

  val deltaInsertMessage = "{\"type\":\"delta\",\"delta\":{\"ops\":[{\"insert\":\"f\"}]}}"
  val deltaInsert = DeltaMessage(List(InsertString("f")))

  val deltaRetainMessage = "{\"type\":\"delta\",\"delta\":{\"ops\":[{\"retain\":4}]}}"
  val deltaRetain = DeltaMessage(List(Retain(4)))

  val combinedDeltaMessage = "{\"type\":\"delta\",\"delta\":{\"ops\":[{\"retain\":9},{\"insert\":\"f\"}]}}"
  val combinedDelta = DeltaMessage(List(
    Retain(9),
    InsertString("f")
  ))

  val mm2Msg="{\"type\":\"delta\",\"delta\":{\"ops\":[{\"insert\":\"a\"}]}}"
  val mm2 = DeltaMessage(List(InsertString("a")))
}
