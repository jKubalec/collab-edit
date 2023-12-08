package concurentDocs.app.domain

import concurentDocs.app.domain.UserDomain.User

object TextEditorDomain {

  case class EditorUpdateMessage(delta: DeltaMessage, oldDelta: DeltaMessage, source: String) //  what is this?

  sealed trait EditorMessage

  sealed class SystemMessage extends EditorMessage
  case object Ping extends SystemMessage
  case class Login(user: User) extends SystemMessage
  case object Logout extends SystemMessage
  case class Welcome(user: User) extends SystemMessage

  case object ContentRequest extends SystemMessage

  case object ClearContent extends SystemMessage

  case class EditorContent(content: String, deltas: DeltaMessage) extends SystemMessage

  sealed class EditorDelta

  case class DeltaMessage(ops: List[EditorDelta]) extends EditorMessage
  sealed class EditorDeltaInsert extends EditorDelta
  case class InsertString(insert: String) extends EditorDeltaInsert
  case class InsertStringWithAttributes(insert: String, attributes: Map[String, AttributeValue]) extends EditorDeltaInsert
  case class InsertEmbed(insert: Map[String, String]) extends EditorDeltaInsert
  case class InsertEmbedWithAttributes(insert: Map[String, String], attributes: Map[String, AttributeValue])extends EditorDeltaInsert

  sealed class EditorRetain extends EditorDelta
  case class Retain(retain: Int) extends EditorRetain
  case class SetAttributes(retain: Int, attributes: Map[String, AttributeValue]) extends EditorRetain

  sealed class AttributeValue
  case class AttributeString(value: String) extends AttributeValue
  case class AttributeInt(value: Int) extends AttributeValue
  case class AttributeBool(value: Boolean) extends AttributeValue

  case class Delete(delete: Int) extends EditorDelta
}
