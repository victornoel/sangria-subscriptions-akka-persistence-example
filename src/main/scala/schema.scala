import java.util.UUID

import akka.actor.ActorRef
import akka.util.Timeout
import generic.{Event, View, Versioned}
import generic.View.Get
import sangria.execution.UserFacingError
import sangria.schema._
import akka.pattern.ask

import scala.annotation.unchecked.uncheckedVariance
import scala.collection.immutable.ListMap
import scala.concurrent.ExecutionContext
import scala.reflect.ClassTag

object schema {
  case class MutationError(message: String) extends Exception(message) with UserFacingError
  case class SubscriptionField[+T : ClassTag](tpe: ObjectType[Ctx, T @uncheckedVariance]) {
    lazy val clazz = implicitly[ClassTag[T]].runtimeClass

    def value(v: Any): Option[T] = v match {
      case v if clazz.isAssignableFrom(v.getClass) ⇒ Some(v.asInstanceOf[T])
      case _ ⇒ None
    }
  }

  val EventType = InterfaceType("Event", fields[Ctx, Event](
    Field("id", StringType, resolve = _.value.id),
    Field("version", LongType, resolve = _.value.version)))

  val AuthorCreatedType = ObjectType(
    "AuthorCreated",
    interfaces[Unit, AuthorCreated](EventType),
    fields[Unit, AuthorCreated](
      Field("firstName", StringType, resolve = _.value.firstName),
      Field("lastName", StringType, resolve = _.value.lastName)))

  val AuthorNameChangedType = ObjectType(
    "AuthorNameChanged",
    interfaces[Unit, AuthorNameChanged](EventType),
    fields[Unit, AuthorNameChanged](
      Field("firstName", StringType, resolve = _.value.firstName),
      Field("lastName", StringType, resolve = _.value.lastName)))

  val AuthorDeletedType = ObjectType("AuthorDeleted", interfaces[Unit, AuthorDeleted](EventType), Nil)

  val ArticleCreatedType = ObjectType(
    "ArticleCreated",
    interfaces[Unit, ArticleCreated](EventType),
    fields[Unit, ArticleCreated](
      Field("title", StringType, resolve = _.value.title),
      Field("authorId", StringType, resolve = _.value.authorId),
      Field("text", OptionType(StringType), resolve = _.value.text)))

  val ArticleTextChangedType = ObjectType(
    "ArticleTextChanged",
    interfaces[Unit, ArticleTextChanged](EventType),
    fields[Unit, ArticleTextChanged](
      Field("text", OptionType(StringType), resolve = _.value.text)))

  val ArticleDeletedType = ObjectType("ArticleDeleted", interfaces[Unit, ArticleDeleted](EventType), Nil)

  val SubscriptionFields = ListMap[String, SubscriptionField[Event]](
    "authorCreated" → SubscriptionField(AuthorCreatedType),
    "authorNameChanged" → SubscriptionField(AuthorNameChangedType),
    "authorDeleted" → SubscriptionField(AuthorDeletedType),
    "articleCreated" → SubscriptionField(ArticleCreatedType),
    "articleTextChanged" → SubscriptionField(ArticleTextChangedType),
    "articleDeleted" → SubscriptionField(ArticleDeletedType))

  def subscriptionFieldName(event: Event) =
    SubscriptionFields.find(_._2.clazz.isAssignableFrom(event.getClass)).map(_._1)

  def createSchema(implicit timeout: Timeout, ec: ExecutionContext) = {
    val VersionedType = InterfaceType("Versioned", fields[Ctx, Versioned](
      Field("id", StringType, resolve = _.value.id),
      Field("version", LongType, resolve = _.value.version)))

    val AuthorType = ObjectType("Author", interfaces[Unit, Author](VersionedType), fields[Unit, Author](
      Field("firstName", StringType, resolve = _.value.firstName),
      Field("lastName", StringType, resolve = _.value.lastName)))

    val ArticleType = ObjectType("Article", interfaces[Ctx, Article](VersionedType), fields[Ctx, Article](
      Field("title", StringType, resolve = _.value.title),
      Field("author", OptionType(AuthorType), resolve = c ⇒
        (c.ctx.authors ? Get(c.value.authorId)).mapTo[Option[Author]]),
      Field("text", OptionType(StringType), resolve = _.value.text)))

    val IdArg = Argument("id", StringType)
    val VersionArg = Argument("version", LongType)

    val OffsetArg = Argument("offset", OptionInputType(IntType), 0)
    val LimitArg = Argument("limit", OptionInputType(IntType), 100)

    def entityFields[T : ClassTag](name: String, tpe: ObjectType[Ctx, T], actor: Ctx ⇒ ActorRef) = fields[Ctx, Any](
      Field(name, OptionType(tpe),
        arguments = IdArg :: Nil,
        resolve = c ⇒ (actor(c.ctx) ? Get(c.arg(IdArg))).mapTo[Option[T]]),
      Field(name + "s", ListType(tpe),
        arguments = OffsetArg :: LimitArg :: Nil,
        resolve = c ⇒ (actor(c.ctx) ? View.List(c.arg(OffsetArg), c.arg(LimitArg))).mapTo[Seq[T]]))

    val QueryType = ObjectType("Query",
      entityFields[Author]("author", AuthorType, _.authors) ++
      entityFields[Article]("article", ArticleType, _.articles))

    val FirstNameArg = Argument("firstName", StringType)
    val LastNameArg = Argument("lastName", StringType)

    val TitleArg = Argument("title", StringType)
    val AuthorIdArg = Argument("authorId", StringType)
    val TextArg = Argument("text", OptionInputType(StringType))

    val MutationType = ObjectType("Mutation", fields[Ctx, Any](
      Field("createAuthor", OptionType(AuthorType),
        arguments = FirstNameArg :: LastNameArg :: Nil,
        resolve = c ⇒
          c.ctx.addEvent[Author](c.ctx.authors,
            AuthorCreated(UUID.randomUUID.toString, 1, c.arg(FirstNameArg), c.arg(LastNameArg)))),

      Field("changeAuthorName", OptionType(AuthorType),
        arguments = IdArg :: VersionArg :: FirstNameArg :: LastNameArg :: Nil,
        resolve = c ⇒
          c.ctx.loadLatestVersion(c.arg(IdArg), c.arg(VersionArg)) flatMap (version ⇒
            c.ctx.addEvent[Author](c.ctx.authors,
              AuthorNameChanged(c.arg(IdArg), version, c.arg(FirstNameArg), c.arg(LastNameArg))))),

      Field("deleteAuthor", OptionType(AuthorType),
        arguments = IdArg :: VersionArg :: Nil,
        resolve = c ⇒
            for {
              version ← c.ctx.loadLatestVersion(c.arg(IdArg), c.arg(VersionArg))
              author ← (c.ctx.authors ? Get(c.arg(IdArg))).mapTo[Option[Author]]
              _ ← c.ctx.addDeleteEvent(AuthorDeleted(c.arg(IdArg), version))
            } yield author),

      Field("createArticle", OptionType(ArticleType),
        arguments = TitleArg :: AuthorIdArg :: TextArg :: Nil,
        resolve = c ⇒ (c.ctx.authors ? Get(c.arg(AuthorIdArg))) flatMap {
          case Some(author: Author) ⇒
            c.ctx.addEvent[Article](c.ctx.articles,
              ArticleCreated(UUID.randomUUID.toString, 1, c.arg(TitleArg), author.id, c.arg(TextArg)))
          case _ ⇒
            throw MutationError(s"Author with ID '${c.arg(AuthorIdArg)}' does not exist.")
        }),

      Field("changeArticleText", OptionType(ArticleType),
        arguments = IdArg :: VersionArg :: TextArg :: Nil,
        resolve = c ⇒
          c.ctx.loadLatestVersion(c.arg(IdArg), c.arg(VersionArg)) flatMap (version ⇒
            c.ctx.addEvent[Article](c.ctx.articles,
              ArticleTextChanged(c.arg(IdArg), version, c.arg(TextArg))))),

      Field("deleteArticle", OptionType(ArticleType),
        arguments = IdArg :: VersionArg :: Nil,
        resolve = c ⇒
          for {
            version ← c.ctx.loadLatestVersion(c.arg(IdArg), c.arg(VersionArg))
            author ← (c.ctx.articles ? Get(c.arg(IdArg))).mapTo[Option[Article]]
            _ ← c.ctx.addDeleteEvent(ArticleDeleted(c.arg(IdArg), version))
          } yield author)))

    val SubscriptionType = ObjectType("Subscription",
      SubscriptionFields.toList.map { case (name, field) ⇒
        Field(name, OptionType(field.tpe), resolve = (c: Context[Ctx, Any]) ⇒ field.value(c.value))
      })

    Schema(QueryType, Some(MutationType), Some(SubscriptionType))
  }
}
