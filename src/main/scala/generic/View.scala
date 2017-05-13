package generic

import akka.actor.{Actor, ActorLogging, ActorRef}
import akka.stream.actor.OneByOneRequestStrategy

import scala.collection.immutable.ListMap
import scala.concurrent.duration._
import scala.language.postfixOps

abstract class View[Entity <: Versioned, Ev <: Event] extends Actor with ActorLogging {
  import View._

  private var entities = ListMap.empty[String, Entity]
  private var waiting = Map.empty[(String, Long), ActorRef]

  import context.dispatcher

  def receive = {
    case Init ⇒ sender() ! Ack
    case event: Event ⇒
      if (handleEvent.isDefinedAt(event.asInstanceOf[Ev])) {
        handleEvent(event.asInstanceOf[Ev])

        val waitingKey = event.id → event.version

        waiting.get(waitingKey) foreach { senderRef ⇒
          senderRef ! entities.get(event.id)
          waiting = waiting.filterNot(_._1 == waitingKey)
        }
      }
      sender() ! Ack
    case RemoveWaiting(key) ⇒
      waiting.get(key) foreach { senderRef ⇒
        senderRef ! None
        waiting = waiting.filterNot(_._1 == key)
      }
    case List(offset, limit) ⇒
      sender() ! entities.values.slice(offset, offset + limit)
    case Get(id, None) ⇒
      sender() ! entities.get(id)
    case GetMany(ids) ⇒
      sender() ! entities.collect{case (key, value) if ids.contains(key) ⇒ value}.toVector
    case Get(id, Some(version)) ⇒
      entities.get(id) match {
        case Some(entity) if entity.version == version ⇒
          sender() ! entities.get(id)
        case _ ⇒
          waiting = waiting + ((id → version) → sender())
          context.system.scheduler.scheduleOnce(5 seconds, self, RemoveWaiting(id → version))
      }
  }

  def add(entity: Entity) =
    entities = entities + (entity.id → entity)

  def update(event: Ev)(fn: Entity ⇒ Entity) =
    change(event)(entity ⇒ entities = entities.updated(entity.id, fn(entity)))

  def delete(event: Ev) =
    change(event)(entity ⇒ entities = entities.filterNot(_._1 == entity.id))

  private def change(event: Ev)(fn: Entity ⇒ Unit) =
    entities.get(event.id) match {
      case Some(entity) if entity.version != event.version - 1 ⇒
        log.error(s"Entity ${event.id}: version mismatch: expected ${entity.version + 1}, but got ${event.version}")
      case Some(entity) ⇒
        fn(entity)
      case None ⇒
        log.error(s"Entity ${event.id}: not found")
    }

  val requestStrategy = OneByOneRequestStrategy

  def handleEvent: Handler

  type Handler = PartialFunction[Ev, Unit]
}

object View {
  case class List(offset: Int, limit: Int)
  case class Get(id: String, version: Option[Long] = None)
  case class GetMany(ids: Seq[String])

  private case class RemoveWaiting(key: (String, Long))

  case object Ack
  case object Init
}