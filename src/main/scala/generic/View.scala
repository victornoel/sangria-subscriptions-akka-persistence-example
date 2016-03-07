package generic

import language.postfixOps

import akka.actor.{ActorLogging, ActorRef}
import akka.stream.actor.{OneByOneRequestStrategy, ActorSubscriber}

import akka.stream.actor.ActorSubscriberMessage._

import scala.collection.immutable.ListMap
import scala.concurrent.duration._

abstract class View[Entity <: Versioned, Ev <: Event] extends ActorSubscriber with ActorLogging {
  import View._

  private var entities = ListMap.empty[String, Entity]
  private var waiting = Map.empty[(String, Long), ActorRef]

  import context.dispatcher

  def receive = {
    case OnNext(event: Event) if handleEvent.isDefinedAt(event.asInstanceOf[Ev]) ⇒
      handleEvent(event.asInstanceOf[Ev])

      val waitingKey = event.id → event.version

      waiting.get(waitingKey) foreach { senderRef ⇒
        senderRef ! entities.get(event.id)
        waiting = waiting.filterNot(_._1 == waitingKey)
      }
    case RemoveWaiting(key) ⇒
      waiting.get(key) foreach { senderRef ⇒
        senderRef ! None
        waiting = waiting.filterNot(_._1 == key)
      }
    case List(offset, limit) ⇒
      sender() ! entities.values.slice(offset, offset + limit)
    case Get(id, None) ⇒
      sender() ! entities.get(id)
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

  private case class RemoveWaiting(key: (String, Long))
}