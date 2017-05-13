package generic

import akka.persistence.{PersistentActor, ReplyToStrategy, SnapshotOffer}

class PersistentEventStore extends PersistentActor {
  import PersistentEventStore._

  override def persistenceId = storeId

  override def internalStashOverflowStrategy = ReplyToStrategy(OverCapacity)

  // internal state, only the latest version is useful for commands
  var eventVersions = Map.empty[String, Long]

  private def updateState(event: Event) = {
    eventVersions = eventVersions + (event.id → event.version)
  }

  val receiveRecover = {
    case event: Event ⇒ updateState(event)
    // not used for now
    case SnapshotOffer(_, snapshot: Map[String, Long]) ⇒ eventVersions = snapshot
  }

  val receiveCommand = {
    case LatestEventVersion(id) ⇒
      sender() ! eventVersions.get(id)

    case AddEvent(event) ⇒ persist(event) { event ⇒
      eventVersions.get(event.id) match {
        case None ⇒
          updateState(event)
          sender() ! EventAdded(event)
        case Some(version) ⇒
          if (version == event.version - 1) {
            updateState(event)
            sender() ! EventAdded(event)
          } else {
            sender() ! ConcurrentModification(event, version)
          }
      }
    }
    case Shutdown ⇒ context.stop(self)
  }
}

object PersistentEventStore {
  case class AddEvent(event: Event)
  case class LatestEventVersion(id: String)
  case object Shutdown

  case class EventAdded(event: Event)
  case class ConcurrentModification(event: Event, latestVersion: Long)
  case object OverCapacity

  val storeId = "event-store-0"
}