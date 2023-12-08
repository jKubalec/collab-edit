package concurentDocs.app.json

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import concurentDocs.app.domain.TextEditorDomain
import concurentDocs.app.domain.UserDomain.User
import spray.json._

trait EditorUpdateMessageJsonProtocol extends SprayJsonSupport with DefaultJsonProtocol {
  import TextEditorDomain._

  implicit val userValueFormat: RootJsonFormat[User] = jsonFormat1(User)

  implicit val systemMessageFormat: RootJsonFormat[SystemMessage] = new RootJsonFormat[SystemMessage] {
    override def write(msg: SystemMessage): JsValue = msg match {
      case Ping => JsObject("type" -> JsString("Ping"))
      case Login(user) => JsObject("type" -> JsString("Login"), "user" -> userValueFormat.write(user))
      case Logout => JsObject("type" -> JsString("Logout"))
      case Welcome(user) => JsObject("type" -> JsString("Welcome"), "user" -> userValueFormat.write(user))
      case ContentRequest => JsObject("type" -> JsString("ContentRequest"))
      case ClearContent => JsObject("type" -> JsString("ClearContent"))
      case EditorContent(content, deltas) => JsObject("type" -> JsString("EditorContent"),
        "content" -> JsString(content),
        "deltas" -> JsObject("ops" -> deltas.ops.toJson)
      )
    }

    override def read(json: JsValue): SystemMessage = {
      json.asJsObject.getFields("type") match {
        case Seq(JsString("Ping")) => Ping
        case Seq(JsString("Login")) => Login(json.asJsObject.getFields("user").head.convertTo[User])
        case Seq(JsString("Logout")) => Logout
        case Seq(JsString("Welcome")) => Welcome(json.asJsObject.getFields("user").head.convertTo[User])
        case Seq(JsString("ContentRequest")) => ContentRequest
        case Seq(JsString("ClearContent")) => ClearContent
        case Seq(JsString("EditorContent")) =>
          json.asJsObject.getFields("content", "deltas") match {
            case Seq(JsString(content), JsArray(deltas)) =>
              EditorContent(content, DeltaMessage(deltas.map(_.convertTo[EditorDelta]).toList))
            case msg => throw DeserializationException("EditorContent expected")
          }
        case _ => throw DeserializationException("SystemMessage expected")
      }
    }
  }

  implicit val editorMessageFormat: RootJsonFormat[EditorMessage] = new RootJsonFormat[EditorMessage] {
    override def write(msg: EditorMessage): JsValue = msg match {
      case d : DeltaMessage => JsObject("type" -> JsString("delta"), "delta" -> deltaMessageFormat.write(d))
      case m : SystemMessage => systemMessageFormat.write(m)
    }

    override def read(json: JsValue): EditorMessage = {
      json.asJsObject.getFields("type") match {
        case Seq(JsString("delta")) =>
          json.asJsObject.getFields("delta") match {
            case Seq(ops) =>
              DeltaMessage(ops.asJsObject.getFields("ops").head.convertTo[List[EditorDelta]])
            case _ => throw DeserializationException("Invalid DeltaMessage")
          }
        case Seq(JsString(_)) =>
          systemMessageFormat.read(json)
        case _ => throw DeserializationException("Unknown EditorMessage")
      }
    }
  }

  implicit val retainMessageFormat: RootJsonFormat[Retain] = jsonFormat1(Retain)

  implicit val attributeValueFormat: RootJsonFormat[AttributeValue] = new RootJsonFormat[AttributeValue] {
    def write(attributeValue: AttributeValue): JsValue = attributeValue match {
      case AttributeString(stringValue) => JsString(stringValue)
      case AttributeInt(intValue) => JsNumber(intValue)
      case AttributeBool(boolValue) => JsBoolean(boolValue)
    }

    def read(json: JsValue): AttributeValue = json match {
      case JsBoolean(b) => AttributeBool(b)
      case JsNumber(n) => AttributeInt(n.toIntExact)
      case JsString(s) => AttributeString(s)
      case _ => deserializationError("Expected Boolean, Int, or String")
    }
  }

  implicit val retainWAttrsMessageFormat: RootJsonFormat[SetAttributes] = jsonFormat2(SetAttributes)
  implicit val deleteMessageFormat: RootJsonFormat[Delete] = jsonFormat1(Delete)

  implicit val insertStringFormat: RootJsonFormat[InsertString] = jsonFormat1(InsertString)
  implicit val insertStringWithAttributesFormat: RootJsonFormat[InsertStringWithAttributes] = jsonFormat2(InsertStringWithAttributes)
  implicit val insertEmbedFormat: RootJsonFormat[InsertEmbed] = jsonFormat1(InsertEmbed)
  implicit val insertEmbedWithAttributesFormat: RootJsonFormat[InsertEmbedWithAttributes] = jsonFormat2(InsertEmbedWithAttributes)

  implicit val editorDeltaInsertFormat: RootJsonFormat[EditorDeltaInsert] = new RootJsonFormat[EditorDeltaInsert] {
    def write(editorDeltaInsert: EditorDeltaInsert): JsValue = editorDeltaInsert match {
      case insertString: InsertString => insertStringFormat.write(insertString)
      case insertStringWithAttributes: InsertStringWithAttributes => insertStringWithAttributesFormat.write(insertStringWithAttributes)
      case insertEmbed: InsertEmbed => insertEmbedFormat.write(insertEmbed)
      case insertEmbedWithAttributes: InsertEmbedWithAttributes => insertEmbedWithAttributesFormat.write(insertEmbedWithAttributes)
    }

    def read(json: JsValue): EditorDeltaInsert = {
      val jsonAsObj = json.asJsObject
      jsonAsObj.getFields("insert") match {
        case insertEmbed: JsObject =>
          insertEmbed match {
            case insertEmbedWAttrs: JsObject if insertEmbedWAttrs.fields.contains("attributes") =>
              insertEmbedWithAttributesFormat.read(insertEmbedWAttrs)
            case _ => insertEmbedFormat.read(insertEmbed)
          }
        case _: List[JsString] =>
          if (jsonAsObj.fields.contains("attributes")) insertStringWithAttributesFormat.read(json)
          else insertStringFormat.read(json)
      }
    }
  }

  implicit val editorRetainFormat: RootJsonFormat[EditorRetain] = new RootJsonFormat[EditorRetain] {
    override def read(json: JsValue): EditorRetain = json match {
      case retain: JsObject if retain.fields.contains("retain") =>
        retain match {
          case retainWAttrs: JsObject if retainWAttrs.fields.contains("attributes") => retainWAttrsMessageFormat.read(retainWAttrs)
          case _ => retainMessageFormat.read(retain)
        }
      case unknown => deserializationError(s"unknown Editor Object: $unknown")
    }

    override def write(editorRetain: EditorRetain): JsValue = editorRetain match {
      case retain: Retain => retainMessageFormat.write(retain)
      case retainWAttrs: SetAttributes => retainWAttrsMessageFormat.write(retainWAttrs)
    }
  }

  implicit val editorDeltaFormat: RootJsonFormat[EditorDelta] = new RootJsonFormat[EditorDelta] {
    override def read(json: JsValue): EditorDelta = json match {
      case editorDeltaInsert: JsObject if editorDeltaInsert.fields.contains("insert") =>
        editorDeltaInsertFormat.read(editorDeltaInsert)
      case editorRetain: JsObject if editorRetain.fields.contains("retain") =>
        editorRetainFormat.read(editorRetain)
      case editorDelete: JsObject if editorDelete.fields.contains("delete") =>
        deleteMessageFormat.read(editorDelete)
      case unknown => deserializationError(s"unknown Editor Object: $unknown")
    }

    override def write(editorDelta: EditorDelta): JsValue = editorDelta match {
      case insert: EditorDeltaInsert => editorDeltaInsertFormat.write(insert)
      case retain: EditorRetain => editorRetainFormat.write(retain)
      case delete: Delete => deleteMessageFormat.write(delete)
    }
  }
  implicit val deltaMessageFormat: RootJsonFormat[DeltaMessage] = jsonFormat1(DeltaMessage)
  implicit val editorUpdateMessageFormat: RootJsonFormat[EditorUpdateMessage] = jsonFormat3(EditorUpdateMessage)
}