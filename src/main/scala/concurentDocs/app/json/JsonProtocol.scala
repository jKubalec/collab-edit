package concurentDocs.app.json

import concurentDocs.app.domain.TextEditorDomain
import concurentDocs.app.domain.UserDomain.User
import io.circe._
import io.circe.generic.semiauto._
import io.circe.syntax._


object JsonProtocol {

  import TextEditorDomain._

  implicit val userEncoder: Encoder.AsObject[User] = deriveEncoder[User]
  implicit val userDecoder: Decoder[User] = deriveDecoder[User]

  implicit val attributeValueEncoder: Encoder[AttributeValue] = deriveEncoder[AttributeValue]
  implicit val attributeValueDecoder: Decoder[AttributeValue] = deriveDecoder[AttributeValue]

  implicit val editorDeltaInsertEncoder: Encoder[EditorDeltaInsert] = {
    case InsertString(s) => Json.obj("insert" -> s.asJson)
    case InsertStringWithAttributes(s, attrs) => Json.obj("insert" -> s.asJson, "attributes" -> attrs.asJson)
    case InsertEmbed(embed) => Json.obj("insert" -> embed.asJson)
    case InsertStringWithAttributes(embed, attrs) => Json.obj("insert" -> embed.asJson, "attributes" -> attrs.asJson)
  }

  implicit val editorDeltaInsertDecoder: Decoder[EditorDeltaInsert] = (cursor: io.circe.HCursor) => {
    implicit val insertDecoder: Decoder[Either[String, Map[String, String]]] = (c: HCursor) => {
      val asString = c.as[String]
      val asMap = c.as[Map[String, String]]
      asString match {
        case Right(value) => Right(Left(value))
        case Left(_) => asMap.map(Right(_))
      }
    }

    for {
      insertValue <- cursor.downField("insert").as[Either[String, Map[String, String]]]
      attributes <- cursor.downField("attributes").as[Option[Map[String, AttributeValue]]]
    } yield {
      (insertValue, attributes) match {
        case (Left(s), None) => InsertString(s)
        case (Left(s), Some(attrs)) => InsertStringWithAttributes(s, attrs)
        case (Right(embed), None) => InsertEmbed(embed)
        case (Right(embed), Some(attrs)) => InsertEmbedWithAttributes(embed, attrs)
      }
    }
  }

  implicit val editorRetainEncoder: Encoder[EditorRetain] = {
    case Retain(retain) => Json.obj("retain" -> retain.asJson)
    case SetAttributes(retain, attrs) => Json.obj("retain" -> retain.asJson, "attributes" -> attrs.asJson)
  }
  implicit val editorRetainDecoder: Decoder[EditorRetain] = (cursor: io.circe.HCursor) => {
    for {
      retainValue <- cursor.downField("retain").as[Int]
      attrs <- cursor.downField("attributes").as[Option[Map[String, AttributeValue]]]
    } yield
      (retainValue, attrs) match {
        case (retain, None) => Retain(retain)
        case (retain, Some(attrs)) => SetAttributes(retain, attrs)
      }
  }

  implicit val editorDeleteEncoder: Encoder[EditorDelete] = deriveEncoder[EditorDelete]
  implicit val editorDeleteDecoder: Decoder[EditorDelete] = deriveDecoder[EditorDelete]

  implicit val deltaMessageEncoder: Encoder[DeltaMessage] = deriveEncoder[DeltaMessage]
  implicit val deltaMessageDecoder: Decoder[DeltaMessage] = deriveDecoder[DeltaMessage]

  implicit val deltaEncoder: Encoder[EditorDelta] = {
    case insert: EditorDeltaInsert => editorDeltaInsertEncoder(insert)
    case retain: EditorRetain => editorRetainEncoder(retain)
    case delete: EditorDelete => editorDeleteEncoder(delete)
  }

  implicit val deltaDecoder: Decoder[EditorDelta] = (cursor: io.circe.HCursor) => {
    cursor.keys match {
      case Some(keys) if keys.exists(_ == "retain") => cursor.as[EditorRetain]
      case Some(keys) if keys.exists(_ == "insert") => cursor.as[EditorDeltaInsert]
      case Some(keys) if keys.exists(_ == "delete") => cursor.as[EditorDelete]
      case _ => Left(DecodingFailure("Unknown EditorDelta", cursor.history))
    }
  }

  implicit val editorContentEncoder: Encoder[EditorContent] = { editorContent =>
    Json.obj(
      "type" -> "EditorContent".asJson,
      "deltas" -> editorContent.deltas.ops.asJson,
      "content" -> editorContent.content.asJson
    )
  }

  implicit val editorContentDecoder: Decoder[EditorContent] = (cursor: io.circe.HCursor) => {
    cursor.keys match {
      case Some(keys) if
        (keys.exists(_ == "content")
          && keys.exists(_ == "deltas")) =>
        for {
          content <- cursor.downField("content").as[String]
          deltas <- cursor.downField("deltas").as[List[EditorDelta]]
        } yield EditorContent(content, DeltaMessage(deltas))
    }
  }

  implicit val systemMessageEncoder: Encoder[SystemMessage] = {
    case Ping => Json.obj("type" -> "Ping".asJson)
    case Login(user) => Json.obj("type" -> "Login".asJson, "user" -> user.asJson)
    case Logout => Json.obj("type" -> "Logout".asJson)
    case Welcome(user) => Json.obj("type" -> "Welcome".asJson, "user" -> user.asJson)
    case ContentRequest => Json.obj("type" -> "ContentRequest".asJson)
    case ClearContent => Json.obj("type" -> "ClearContent".asJson)
    case EditorContent(content, deltas) =>
      Json.obj(
        "type" -> "EditorContent".asJson,
        "content" -> content.asJson,
        "deltas" -> deltas.asJson
      )
  }

  implicit val systemMessageDecoder: Decoder[SystemMessage] = (cursor: io.circe.HCursor) => {
    cursor.downField("type").as[String].flatMap {
      case "Ping" => Right(Ping)
      case "Login" => cursor.downField("user").as[User].map(Login)
      case "Logout" => Right(Logout)
      case "Welcome" => cursor.downField("user").as[User].map(Welcome)
      case "ContentRequest" => Right(ContentRequest)
      case "ClearContent" => Right(ClearContent)
      case "EditorContent" => for {
        content <- cursor.downField("content").as[String]
        deltas <- cursor.downField("deltas").as[DeltaMessage]
      } yield EditorContent(content, deltas)
      case _ => Left(DecodingFailure("Unknown SystemMessage", cursor.history))
    }
  }

  implicit val frontendMessageEncoder: Encoder[FrontendMessage] = {
    case d: DeltaMessage => Json.obj("type" -> "delta".asJson,
      "delta" -> d.asJson(implicitly[Encoder[DeltaMessage]]))
    case s: SystemMessage => s.asJson(implicitly[Encoder[SystemMessage]])
  }

  implicit val frontendMessageDecoder: Decoder[FrontendMessage] = (c: io.circe.HCursor) => {
    c.downField("type").as[String].flatMap {
      case "delta" => c.downField("delta").as[DeltaMessage]
      case "EditorContent" => c.as[EditorContent]
      case _ => c.as[SystemMessage]
    }
  }
}
