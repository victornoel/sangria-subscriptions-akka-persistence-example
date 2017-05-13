import akka.NotUsed
import akka.actor.ActorRef
import akka.pattern.ask
import akka.stream.OverflowStrategy
import akka.stream.scaladsl.Source
import akka.util.Timeout
import generic.Event
import generic.PersistentEventStore._
import generic.View.{Get, GetMany}
import schema.MutationError

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.language.postfixOps

case class Ctx(
  authors: ActorRef,
  articles: ActorRef,
  eventStore: ActorRef,
  events: Source[Event, NotUsed],
  ec: ExecutionContext,
  to: Timeout
) extends Mutation {
  implicit def executionContext = ec
  implicit def timeout = to

  // TODO why buffer?
  lazy val eventStream: Source[Event, NotUsed] =
    events.buffer(100, OverflowStrategy.fail).backpressureTimeout(3 seconds)

  def addEvent[T](view: ActorRef, event: Event) =
    (eventStore ? AddEvent(event)).flatMap {
      case EventAdded(_) ⇒
        (view ? Get(event.id, Some(event.version))).mapTo[Option[T]]
      case OverCapacity ⇒
        throw MutationError("Service is overloaded.")
      case ConcurrentModification(_, latestVersion) ⇒
        throw MutationError(s"Concurrent Modification error for entity '${event.id}'. Latest entity version is '$latestVersion'.")
    }

  def addDeleteEvent(event: Event) =
    (eventStore ? AddEvent(event)).map {
      case EventAdded(e) ⇒ e
      case OverCapacity ⇒
        throw MutationError("Service is overloaded.")
      case ConcurrentModification(_, latestVersion) ⇒
        throw MutationError(s"Concurrent Modification error for entity '${event.id}'. Latest entity version is '$latestVersion'.")
    }

  def loadLatestVersion(id: String, version: Long): Future[Long] =
    (eventStore ? LatestEventVersion(id)) map {
      case Some(latestVersion: Long) if version != latestVersion ⇒
        throw MutationError(s"Concurrent Modification error for entity '$id'. Latest entity version is '$latestVersion'.")
      case Some(version: Long) ⇒
        version + 1
      case _ ⇒
        throw MutationError(s"Entity with ID '$id' does not exist.")
    }

  def loadAuthors(ids: Seq[String]) =
    (authors ? GetMany(ids)).mapTo[Seq[Author]]
}
