package scala.actors.controlflow

import scala.actors.Actor
import scala.actors.controlflow.ControlFlow._

/**
 * A function taking no arguments where the result is provided
 * asynchronously via a continuation.
 */
trait AsyncFunction0[+R] extends AnyRef {

  /**
   * Execute the function, sending the result to one of the provided
   * continuations.
   */
  def ->(fc: FC[R]): Nothing

  def ->[A](g: AsyncFunction0[A]): AsyncFunction0[A] = new AsyncFunction0[A] {
    def ->(fc: FC[A]) = {
      assert(fc != null)
      import fc.implicitThr
      AsyncFunction0.this -> fc0 { g -> fc }
    }
  }

  def ->[A](g: AsyncFunction1[R, A]): AsyncFunction0[A] = andThen(g)

  /**
   * Apply this function. The result will be provided via one of the given
   * <code>FC</code>'s continuations: either <code>ret</code> or
   * <code>thr</code>.
   */
  final def apply(fc: FC[R]): Nothing = ->(fc)

  /**
   * Create a function which executes this function then passes its result to
   * the given function.
   */
  def andThen[A](g: AsyncFunction1[R, A]) = new AsyncFunction0[A] {
    def ->(fc: FC[A]) = {
      assert(fc != null)
      import fc.implicitThr
      AsyncFunction0.this.apply { result: R => g(result)(fc) }
    }
  }

  /**
   * Apply this function in a separate actor. sending the function's result
   * as a <code>FunctionResult</code> down the returned <code>Channel</code>.
   */
  private def applyInActor: Channel[Any] = {
    val channel = new Channel[Any](Actor.self)
    Actor.actor {
      AsyncFunction0.this.apply { result: FunctionResult[R] =>
        channel ! result
      }
    }
    channel
  }

  /**
   * Converts the message returned by <code>applyInActor</code> into a
   * <code>FunctionResult</code>.
   */
  private def messageResult(msg: Any): FunctionResult[R] = msg match {
    case result: FunctionResult[R] => result
    case TIMEOUT => Throw(new TimeoutException)
    case unknown => Throw(new MatchError(unknown))
  }

  /**
   * Creates a function which wraps this function, adding with a timeout
   * feature. The new function has the same behaviour as this function except
   * that it will continue with a <code>TimeoutException</code> if it takes
   * longer than <code>msec</code> milliseconds to execute.
   */
  def within(msec: Long): AsyncFunction0[R] = new AsyncFunction0[R] {
    def ->(fc: FC[R]): Nothing = {
      assert(fc != null)
      val channel = AsyncFunction0.this.applyInActor
      channel.reactWithin(msec) {
        case msg: Any => messageResult(msg).toAsyncFunction.apply(fc)
      }
    }
  }

  /**
   * Creates a synchronous version of this function. When executed, the function
   * will suspend the current thread and run the underlying asynchronous in a
   * new actor.
   */
  def toFunction: RichFunction0[R] = new RichFunction0[R] {
    def apply: R = resultApply.toFunction.apply
    
    def resultApply: FunctionResult[R] = {
      val channel = AsyncFunction0.this.applyInActor
      channel.receive {
        case msg: Any => messageResult(msg)
      }
    }

    def toAsyncFunction = AsyncFunction0.this
  }

  def map[A](f: R => A): AsyncFunction0[A] = new AsyncFunction0[A] {
    def ->(fc: FC[A]) = {
      import fc.implicitThr
      AsyncFunction0.this -> async1 { f(_: R) } -> fc
    }
  }

  def flatMap[A](f: R => AsyncFunction0[A]) = new AsyncFunction0[A] {
    def ->(fc: FC[A]) = {
      import fc.implicitThr
      AsyncFunction0.this -> fc1 { f(_) -> fc }
    }
  }

  def filter(p: R => Boolean) = new AsyncFunction0[R] {
    def ->(fc: FC[R]) = {
      import fc.implicitThr
      AsyncFunction0.this -> fc1 { r: R => if (p(r)) fc.ret(r) }
    }
  }
}
