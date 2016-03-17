import language.postfixOps

import generic.{Event, MemoryEventStore}

import sangria.ast.OperationType
import sangria.execution.{PreparedQuery, ErrorWithResolver, QueryAnalysisError, Executor}
import sangria.parser.{SyntaxError, QueryParser}
import sangria.marshalling.sprayJson._

import spray.json._

import akka.http.scaladsl.model.StatusCodes._
import akka.stream.actor.{ActorSubscriber, ActorPublisher}
import akka.util.{ByteString, Timeout}
import akka.actor.{Props, ActorSystem}
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import akka.stream.{OverflowStrategy, ActorMaterializer}
import akka.stream.scaladsl.{Framing, Sink, Source}
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.event.Logging
import akka.http.scaladsl.marshalling.ToResponseMarshallable


import de.heikoseeberger.akkasse._
import de.heikoseeberger.akkasse.EventStreamMarshalling._

import scala.concurrent.duration._
import scala.util.control.NonFatal
import scala.util.{Success, Failure}

object Server extends App {
  implicit val system = ActorSystem("server")
  implicit val materializer = ActorMaterializer()
  val logger = Logging(system, getClass)

  import system.dispatcher

  implicit val timeout = Timeout(10 seconds)

  val articlesView = system.actorOf(Props[ArticleView])
  val articlesSink = Sink.fromSubscriber(ActorSubscriber[ArticleEvent](articlesView))

  val authorsView = system.actorOf(Props[AuthorView])
  val authorsSink = Sink.fromSubscriber(ActorSubscriber[AuthorEvent](authorsView))

  val eventStore = system.actorOf(Props[MemoryEventStore])
  val eventStorePublisher =
    Source.fromPublisher(ActorPublisher[Event](eventStore))
      .runWith(Sink.asPublisher(fanout = true))

  // Connect event store to views
  Source.fromPublisher(eventStorePublisher).collect{case event: ArticleEvent ⇒ event}.to(articlesSink).run()
  Source.fromPublisher(eventStorePublisher).collect{case event: AuthorEvent ⇒ event}.to(authorsSink).run()

  val ctx = Ctx(authorsView, articlesView, eventStore, system.dispatcher, timeout)

  val executor = Executor(schema.createSchema)

  def eventStream(preparedQuery: PreparedQuery[Ctx, Any, JsObject]): Source[ServerSentEvent, Any] = {
    val fields = preparedQuery.fields.map(_.field.name).toSet

    Source.fromPublisher(eventStorePublisher)
      .buffer(100, OverflowStrategy.fail)
      .collect {
        case event if schema.subscriptionFieldName(event).fold(false)(fields.contains)  ⇒
          preparedQuery.execute(root = event) map (result ⇒
            ServerSentEvent(result.compactPrint))
      }
      .mapAsync(1)(identity)
      .recover {
        case NonFatal(error) ⇒
          logger.error(error, "Unexpected error during event stream processing.")
          ServerSentEvent(error.getMessage)
      }
  }

  def executeQuery(query: String, operation: Option[String], variables: JsObject = JsObject.empty) =
    QueryParser.parse(query) match {
      case Success(queryAst) ⇒
        queryAst.operationType(operation) match {

          // subscription queries will produce `text/event-stream` response
          case Some(OperationType.Subscription) ⇒
            complete(
              executor.prepare(queryAst, ctx, (), operation, variables)
                .map(preparedQuery ⇒ ToResponseMarshallable(eventStream(preparedQuery)))
                .recover {
                  case error: QueryAnalysisError ⇒ ToResponseMarshallable(BadRequest → error.resolveError)
                  case error: ErrorWithResolver ⇒ ToResponseMarshallable(InternalServerError → error.resolveError)
                })

          // all other queries will just return normal JSON response
          case _ ⇒
            complete(executor.execute(queryAst, ctx, (), operation, variables)
              .map(OK → _)
              .recover {
                case error: QueryAnalysisError ⇒ BadRequest → error.resolveError
                case error: ErrorWithResolver ⇒ InternalServerError → error.resolveError
              })
        }

      case Failure(error: SyntaxError) ⇒
        complete(BadRequest, JsObject(
          "syntaxError" → JsString(error.getMessage),
          "locations" → JsArray(JsObject(
            "line" → JsNumber(error.originalError.position.line),
            "column" → JsNumber(error.originalError.position.column)))))

      case Failure(error) ⇒
        complete(InternalServerError)
    }

  val route: Route =
    (post & path("graphql")) {
      entity(as[JsValue]) { requestJson ⇒
        val JsObject(fields) = requestJson

        val JsString(query) = fields("query")

        val operation = fields.get("operation") collect {
          case JsString(op) ⇒ op
        }

        val vars = fields.get("variables") match {
          case Some(obj: JsObject) ⇒ obj
          case _ ⇒ JsObject.empty
        }

        executeQuery(query, operation, vars)
      }
    } ~
    (get & path("graphql")) {
      parameters('query, 'operation.?) { (query, operation) ⇒
        executeQuery(query, operation)
      }
    } ~
    (get & path("client")) {
      getFromResource("web/client.html")
    } ~
    get {
      getFromResource("web/graphiql.html")
    }

  Http().bindAndHandle(route, "0.0.0.0", 8080)
}
