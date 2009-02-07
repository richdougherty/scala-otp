package scala.actors.controlflow.concurrent

import scala.actors.controlflow.ControlFlow._
import scala.collection.immutable.Queue

class AsyncLock {
  
  sealed trait State
  case object Unlocked extends State
  case class Locked(q: Queue[FC[Unit]]) extends State

  private var state: State = Unlocked

  def lock = new AsyncFunction0[Unit] {
    def ->(fc: FC[Unit]): Nothing = synchronized {
      state match {
        case Unlocked => {
          state = Locked(Queue.Empty)
          fc.ret(())
        }
        case Locked(q) => {
          state = Locked(q + fc)
          Actor.exit
        }
      }
    }
  }

  def tryLock = new AsyncFunction0[Boolean] {
    def ->(fc: FC[Boolean]): Nothing = synchronized {
      state match {
        case Unlocked => {
          state = Locked(Queue.Empty)
          fc.ret(true)
        }
        case Locked(q) => {
          fc.ret(false)
        }
      }
    }
  }

  def unlock: Unit = synchronized {
    state match {
      case Locked(q) => {
        if (q.isEmpty) {
          state = Unlocked
        } else {
          val (head, newQ) = q.dequeue
          state = Locked(newQ)
        }
      }
      case s => throw new IllegalStateException(s.toString)
    }
  }

  /**
   * Create a version of the given function which synchronizes with this lock.
   *
   * <pre>
   * lock.syn(f)(fc)
   * </pre>
   */
  def syn[R](f: AsyncFunction0[R]) = new AsyncFunction0[R] {
    def ->(fc: FC[R]) = lock / f -> fc.fin(async0 { unlock })
  }
}
