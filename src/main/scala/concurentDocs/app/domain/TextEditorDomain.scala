package concurentDocs.app.domain

import concurentDocs.app.domain.UserDomain.User

object TextEditorDomain {

  case class EditorUpdateMessage(delta: DeltaMessage, oldDelta: DeltaMessage, source: String) //  what is this?

  sealed trait FrontendMessage

  sealed trait SystemMessage     extends FrontendMessage

  case object Ping               extends SystemMessage

  case class Login(user: User)   extends SystemMessage

  case object Logout             extends SystemMessage

  case class Welcome(user: User) extends SystemMessage

  case object ContentRequest extends SystemMessage

  case object ClearContent extends SystemMessage

  case class EditorContent(content: String, deltas: DeltaMessage) extends SystemMessage

  sealed trait EditorDelta

  case class DeltaMessage(ops: List[EditorDelta]) extends FrontendMessage

  sealed trait EditorDeltaInsert                  extends EditorDelta

  case class InsertString(insert: String)         extends EditorDeltaInsert

  case class InsertStringWithAttributes(insert: String, attributes: Map[String, AttributeValue])
      extends EditorDeltaInsert

  case class InsertEmbed(insert: Map[String, String]) extends EditorDeltaInsert

  case class InsertEmbedWithAttributes(insert: Map[String, String], attributes: Map[String, AttributeValue])
      extends EditorDeltaInsert

  sealed trait EditorRetain                                                      extends EditorDelta

  case class Retain(retain: Int)                                                 extends EditorRetain

  case class SetAttributes(retain: Int, attributes: Map[String, AttributeValue]) extends EditorRetain

  sealed trait AttributeValue

  case class AttributeString(value: String) extends AttributeValue

  case class AttributeInt(value: Int)       extends AttributeValue

  case class AttributeBool(value: Boolean)  extends AttributeValue

  object AttributeValue {

    def apply(value: Any): AttributeValue = value match {
      case int: Int      => AttributeInt(int)
      case str: String   => AttributeString(str)
      case bool: Boolean => AttributeBool(bool)
      case _             => throw new IllegalArgumentException(s"Invalid attribute value type: $value")
    }

  }

  case class EditorDelete(delete: Int) extends EditorDelta

}
