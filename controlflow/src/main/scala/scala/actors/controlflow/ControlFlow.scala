package scala.actors.controlflow

import scala.actors._
import scala.actors.controlflow.concurrent._

/**
 * Provides useful methods for using asynchronous flow control.
 */
object ControlFlow {

  def cont0(body: => Unit)(implicit thr: Cont[Throwable]): Cont[Unit] =
    cont1 { _: Any => body }

  def cont1[R](f: R => Unit)(implicit thr: Cont[Throwable]): Cont[R] = {
    new Cont[R] {
      def apply(value: R): Nothing = {
        inReaction {
          try {
            f(value)
            Actor.exit
          } catch {
            case t if !isControlFlowThrowable(t) => thr(t)
          }
        }
      }
    }
  }

  def fc0(body: => Unit)(implicit thr: Cont[Throwable]) =
    fc1 { _: Any => body }

  def fc1[R](f: R => Unit)(implicit thr: Cont[Throwable]): FC[R] = {
    val ret: Cont[R] = cont1(f)(thr)
    FC(ret, thr)
  }

  def async0[R](body: => R): AsyncFunction0[R] = new AsyncFunction0[R] {
    def ->(fc: FC[R]) = {
      assert(fc != null)
      // XXX: Could call fc.ret() outside try, to avoid spurious catch of actor exception.
      try { fc.ret(body) } catch { case t if !isControlFlowThrowable(t) => fc.thr(t) }
    }
    // XXX: Implement efficient toRichFunction
  }

  def async1[T1, R](f: T1 => R): AsyncFunction1[T1, R] = new AsyncFunction1[T1, R] {
    def apply(v1: T1) =  async0 { f(v1) }
    // XXX: Implement efficient toRichFunction
  }

  def alazy[A](body: => A) = new AsyncLazyFuture[A](async0(body))

  def asleep(ms: Long) = new AsyncFunction0[Unit] {
    def ->(fc: FC[Unit]) = {
      Actor.reactWithin(ms) {
        case TIMEOUT => fc.ret(())
      }
    }
  }

  private def wrapPartial[R](f: PartialFunction[Any,R], fc: FC[R]) = new PartialFunction[Any,Unit] {
    def isDefinedAt(a: Any) = { a == TIMEOUT || f.isDefinedAt(a) }
    def apply(a: Any): Unit = {
      if (a == TIMEOUT && !f.isDefinedAt(TIMEOUT)) fc.thr(new TimeoutException)
      try {
        fc.ret(f(a))
      } catch {
        case t if !isControlFlowThrowable(t) => fc.thr(t)
      }
    }
  }

  // XXX: Add support for non-self reactions.
  def areact[R](f: PartialFunction[Any,R]) = new AsyncFunction0[R] {
    def ->(fc: FC[R]) = Actor.react(wrapPartial(f, fc))
  }

  // XXX: Add support for non-self reactions.
  def areactWithin[R](ms: Long)(f: PartialFunction[Any,R]) = new AsyncFunction0[R] {
    def ->(fc: FC[R]) = Actor.reactWithin(ms)(wrapPartial(f, fc))
  }

  /**
   * Avoid overflowing the stack by running the given code in a reaction.
   * There may be a more efficient way to do this.
   */
  private def inReaction(body: => Unit): Nothing = {
    val channel = new Channel[Unit](Actor.self)
    channel ! ()
    channel.react {
      case _: Any => body
    }
  }

  // Throwable continuations

  /**
   * Creates a simple Cont[Throwable] that rethrows the given exception when
   * applied.
   *
   * <pre>
   * implicit val implicitThr = thrower
   * val c: Cont[R] = (value: R) => println("Called: " + value)
   * </pre>
   */
  def thrower = new Cont[Throwable] {
    def apply(t: Throwable) = throw t
  }

  // FCs

  /**
   * Creates an FC with continuations that supply a <code>FunctionResult</code>
   * to the given function.
   *
   * <pre>
   * val fc: FC[R] = (result: FunctionResult[R]) => channel ! result
   * </pre>
   */
  def resultFC[R](f: FunctionResult[R] => Unit): FC[R] = {
    // Introduce 'thrower' to avoid recursive use of 'thr'.
    val thr: Cont[Throwable] = cont1((t: Throwable) => f(Throw(t)))(thrower)
    implicit val implicitThr = thr
    val retF = (r: R) => f(Return(r))
    val ret: Cont[R] = cont1(retF)(thr)
    FC(ret, thr)
  }

  // AsyncFunctions

  /**
   * Creates an <code>AsyncFunction0</code> directly from a function with an
   * equivalent signature.
   *
   * <pre>
   * val af: AsyncFunction0[R] => (fc: FC[R]) => fc.ret(...)
   * </pre>
   */
   def asAsync0[R](f: Function1[FC[R], Nothing]): AsyncFunction0[R] = new AsyncFunction0[R] {
    def ->(fc: FC[R]) = {
      assert(fc != null)
      try {
        f(fc)
      } catch {
        case t if !isControlFlowThrowable(t) => fc.thr(t)
      }
    }
  }

  /**
   * Creates an <code>AsyncFunction1</code> directly from a function with an
   * equivalent signature.
   *
   * <pre>
   * val af: AsyncFunction1[T1, R] => (v1: T1, fc: FC[R]) => fc.ret(...)
   * </pre>
   */
  def asAsync1[T1, R](f: Function2[T1, FC[R], Nothing]): AsyncFunction1[T1, R] = new AsyncFunction1[T1, R] {
    def apply(v1: T1) = new AsyncFunction0[R] {
      def ->(fc: FC[R]) = {
        assert(fc != null)
        try {
          f(v1, fc)
        } catch {
          case t if !isControlFlowThrowable(t) => fc.thr(t)
        }
      }
    }
  }

  // Functions

  /**
   * Converts a <code>Function0</code> into a <code>RichFunction0</code>.
   */
  implicit def richFunction0[R](f: Function0[R]) = new RichFunction0[R] {
    self =>

    def apply = f()

    def resultApply: FunctionResult[R] = {
      try {
        val returnValue = f()
        Return(returnValue)
      } catch {
        case t if !isControlFlowThrowable(t) => Throw(t)
      }
    }

    def toAsyncFunction = new AsyncFunction0[R] {
      def ->(fc: FC[R]) = {
        assert(fc != null)
        resultApply.toAsyncFunction -> fc
      }

      override def toFunction: RichFunction0[R] = self

    }

  }

  /**
   * Converts a <code>Function1</code> into a <code>RichFunction1</code>.
   */
  implicit def richFunction1[T1, R](f: Function1[T1, R]) = new RichFunction1[T1, R] {
    self =>

    def apply(v1: T1) = f(v1)

    def resultApply(v1: T1): FunctionResult[R] = {
      try {
        val returnValue = f(v1)
        Return(returnValue)
      } catch {
        case t if !isControlFlowThrowable(t) => Throw(t)
      }
    }

    def toAsyncFunction = new AsyncFunction1[T1, R] {
      def apply(v1: T1) = new AsyncFunction0[R] {
        def ->(fc: FC[R]) = {
          assert(fc != null)
          resultApply(v1).toAsyncFunction -> fc
        }
      }

      override def toFunction: RichFunction1[T1, R] = self
    }

  }

  // Misc

  /**
   * Determine whether or not a given throwable is used by
   * <code>scala.actors</code> to manage for control flow.
   *
   * <p>Currently this is done by treating any non-Exception, non-Error
   * throwable as a control flow throwable. Ideally we need a common superclass
   * for control flow throwables, to make matching correct.
   */
  def isControlFlowThrowable(t: Throwable): Boolean = {
    t match {
      case e: Exception => false
      case e: Error => false
      case _ => true
    }
  }

}
