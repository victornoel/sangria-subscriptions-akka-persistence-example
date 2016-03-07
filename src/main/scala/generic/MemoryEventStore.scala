package generic

import akka.stream.actor.ActorPublisher
import akka.stream.actor.ActorPublisherMessage.{Cancel, Request}

class MemoryEventStore extends ActorPublisher[Event] {
  import MemoryEventStore._

  // in-memory event storage
  var events = Vector.empty[Event]

  var eventBuffer = Vector.empty[Event]

  def receive = {
    case AddEvent(event) if eventBuffer.size >= MaxBufferCapacity ⇒
      sender() ! OverCapacity(event)

    case LatestEventVersion(id) ⇒
      val entityEvents = events.filter(_.id == id)

      if (entityEvents.nonEmpty)
        sender() ! Some(entityEvents.maxBy(_.version).version)
      else
        sender() ! None

    case AddEvent(event) ⇒
      val entityEvents = events.filter(_.id == event.id)

      if (entityEvents.isEmpty) {
        addEvent(event)
        sender() ! EventAdded(event)
      } else {
        val latestEvent = entityEvents.maxBy(_.version)

        if (latestEvent.version == event.version - 1) {
          addEvent(event)
          sender() ! EventAdded(event)
        } else {
          sender() ! ConcurrentModification(event, latestEvent.version)
        }
      }

    case Request(_) ⇒ deliverEvents()
    case Cancel ⇒ context.stop(self)
  }

  def addEvent(event: Event) = {
    events  = events :+ event
    eventBuffer  = eventBuffer :+ event

    deliverEvents()
  }

  def deliverEvents(): Unit = {
    if (isActive && totalDemand > 0) {
      val (use, keep) = eventBuffer.splitAt(totalDemand.toInt)

      eventBuffer = keep

      use foreach onNext
    }
  }
}

object MemoryEventStore {
  case class AddEvent(event: Event)
  case class LatestEventVersion(id: String)

  case class EventAdded(event: Event)
  case class OverCapacity(event: Event)
  case class ConcurrentModification(event: Event, latestVersion: Long)

  val MaxBufferCapacity = 1000
}