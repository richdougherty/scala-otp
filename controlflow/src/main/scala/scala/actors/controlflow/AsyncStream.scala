package scala.actors.controlflow

import scala.actors.controlflow.ControlFlow._

/**
 * The AsyncStream object provides functions for defining and working
 * with asynchronous streams.
 */
object AsyncStream {

  /**
   * An empty AsyncStream.
   */
  val empty = new AsyncStream[Nothing] {
    override def isEmpty: Boolean = true
    override def head: Nothing = throw new NoSuchElementException("head of empty stream")
    def asyncTail(fc: FC[AsyncStream[Nothing]]): Nothing = fc.thr(new UnsupportedOperationException("asyncTail of empty stream"))
    protected def addDefinedElems(buf: StringBuilder, prefix: String): StringBuilder = buf
  }

  /**
   * Create a stream from a given head and an AsyncFunction0 that
   * returns the tail. The tail is evaluated lazily.
   */
  def cons[A](hd: A, getTail: AsyncFunction0[AsyncStream[A]]) = new AsyncStream[A] {
    override def isEmpty = false
    def head = hd
    private var tlCache: AsyncStream[A] = _
    def asyncTail(fc: FC[AsyncStream[A]]): Nothing = {
      import fc.implicitThr
      if (tlCache eq null) {
        getTail { tl: AsyncStream[A] => tlCache = tl; fc.ret(tl) }
      } else {
        fc.ret(tlCache)
      }
    }
    protected def addDefinedElems(buf: StringBuilder, prefix: String): StringBuilder = {
      val buf1 = buf.append(prefix).append(hd)
      if (tlCache eq null) {
        buf1 append ", ?"
      } else {
        tlCache.addDefinedElems(buf1, ", ")
      }
    }
  }

  /**
   * Convert the given list into an AsyncStream.
   */
  def fromList[A](list: List[A]): AsyncStream[A] =
    if (list.isEmpty) AsyncStream.empty
    else cons(list.head, (() => AsyncStream.fromList(list.tail)).toAsyncFunction)
}

/**
 * A stream where the tail is evaluated asynchronously and returned
 * via a continuation. Synchronous access to the tail is still
 * available, but this is relatively heavyweight, since it uses
 * <code>callWithCC</code> internally.
 */
trait AsyncStream[+A] extends Stream[A] {

  def tail = { fc: FC[AsyncStream[A]] => asyncTail(fc) }.toFunction.apply

  /**
   * Get the tail of the list and return it asynchronously, via the
   * given continuation.
   */
  def asyncTail(fc: FC[AsyncStream[A]]): Nothing

  /**
   * Convert the stream to a list. The result is returned
   * asynchronously, via the given continuation.
   */
  def asyncToList(fc: FC[List[A]]): Nothing = {
    // Construct a reversed list by concatenating each element to the
    // head as it is revealed. Then reverse this list to obtain the
    // correct ordering and return the result.
    import fc.implicitThr
    def toReversedList(s: AsyncStream[A], accum: List[A])(fc2: FC[List[A]]): Nothing = {
      if (s.isEmpty) fc2.ret(accum)
      else s.asyncTail { tl: AsyncStream[A] => toReversedList(tl, s.head :: accum)(fc2) }
    }
    toReversedList(this, Nil) { reversed: List[A] => fc.ret(reversed.reverse) }
  }

  /**
   * Perform a 'map' operation on the stream, using the given
   * AsyncFunction1. The result is returned asynchronously, via a
   * continuation.
   */
  def asyncMap[B](f: AsyncFunction1[A, B])(fc: FC[AsyncStream[B]]): Nothing = {
    import fc.implicitThr
    if (isEmpty) fc.ret(AsyncStream.empty)
    else f(head) { mappedHead: B =>
      fc.ret(AsyncStream.cons(mappedHead, { fc2: FC[AsyncStream[B]] =>
        asyncTail {tl: AsyncStream[A] => tl.asyncMap(f)(fc2) }
      }))
    }
  }

  /**
   * Concatenate two streams. The second stream is made available as
   * the result of an AsyncFunction0. The result is returned
   * asynchronously, via a continuation.
   */
  def asyncAppend[B >: A](restGetter: AsyncFunction0[AsyncStream[B]])(fc: FC[AsyncStream[B]]): Nothing = {
    import fc.implicitThr
    if (isEmpty) restGetter(fc)
    else fc.ret(AsyncStream.cons(head, { fc2: FC[AsyncStream[B]] =>
      asyncTail { tl: AsyncStream[A] => tl.asyncAppend(restGetter)(fc2) }
    }))
  }
}
