package scala.actors.controlflow

import scala.actors.controlflow.ControlFlow._
import scala.actors.controlflow.concurrent._

/**
 * The AsyncStream object provides functions for defining and working
 * with asynchronous streams.
 */
object AsyncStream {

  /**
   * An empty AsyncStream.
   */
  val empty: AsyncStream[Nothing] = new AsyncStream[Nothing] {
    override def isEmpty: Boolean = true
    override def head: Nothing = throw new NoSuchElementException("head of empty stream")
    val asyncTail = new AsyncConstantFuture(Throw(new UnsupportedOperationException("asyncTail of empty stream")))
  }

  /**
   * Create a stream from a given head and an AsyncFunction0 that
   * returns the tail. The tail is evaluated lazily.
   */
  def cons[A](hd: A, getTail: AsyncFunction0[AsyncStream[A]]) =
    consFuture(hd, new AsyncLazyFuture(getTail))

  /**
   * Create a stream from a given head and an AsyncFuture that will
   * contain the tail. XXX: Not sure whether to force an
   * AsyncLazyFuture here.
   */
  def consFuture[A](hd: A, tail: AsyncFuture[AsyncStream[A]]) = new AsyncStream[A] {
    override def isEmpty = false
    def head = hd
    val asyncTail = tail
  }

  /**
   * Convert the given list into an AsyncStream.
   */
  def fromList[A](list: List[A]): AsyncStream[A] =
    if (list.isEmpty) AsyncStream.empty
    else cons(list.head, (() => AsyncStream.fromList(list.tail)).toAsyncFunction)

  /**
   * Convert the given iterator into an AsyncStream.
   */
  def fromIterator[A](iterator: Iterator[A]): AsyncStream[A] =
    if (iterator.hasNext) cons(iterator.next, (() => AsyncStream.fromIterator(iterator)).toAsyncFunction)
    else AsyncStream.empty

  /**
   * Convert the given iterable into an AsyncStream.
   */
  def fromIterable[A](iterable: Iterable[A]): AsyncStream[A] =
    fromIterator(iterable.elements)

  def fromAsyncIterator[A](itr: AsyncIterator[A]): AsyncFunction0[AsyncStream[A]] = new AsyncFunction0[AsyncStream[A]] {
    def ->(fc: FC[AsyncStream[A]]): Nothing = {
      import fc.implicitThr
      itr.hasNext -> fc1 { (hasNext: Boolean) =>
        if (hasNext) itr.next / async1 { (next: A) => AsyncStream.cons(next, fromAsyncIterator(itr)) } -> fc
        else fc.ret(AsyncStream.empty)
      }
    }
  }
}

/**
 * A stream where the tail is evaluated asynchronously and returned
 * via a continuation. Synchronous access to the tail is still
 * available, but this is relatively heavyweight, since it uses
 * <code>callWithCC</code> internally.
 */
trait AsyncStream[+A] {

  def isEmpty: Boolean

  def head: A
  
  protected def addDefinedElems(buf: StringBuilder, prefix: String): StringBuilder = {
    val buf1 = buf.append(prefix).append(head)
    asyncTail.result match {
      case None => buf1 append ", ?"
      case Some(result) => result.toFunction.apply.addDefinedElems(buf1, ", ")
    }
  }

  def asyncElements: AsyncIterator[A] = new AsyncIterator[A] {
    var current = AsyncStream.this
    def hasNext = new AsyncFunction0[Boolean] {
      def ->(fc: FC[Boolean]) = {
        val result = !current.isEmpty
        fc.ret(result)
      }
    }
    def next = new AsyncFunction0[A] {
      def ->(fc: FC[A]) = {
        import fc.implicitThr
        // XXX: Consider storing AsyncFuture with current value,
        // to avoid eager loading.
        current.asyncTail -> fc1 { tl: AsyncStream[A] =>
          val result = current.head
          current = tl
          fc.ret(result)
        }
      }
    }
  }


  /*def asyncElements: AsyncIterator[A] = new AsyncIterator[A] {
    var currentFuture: AsyncFuture[AsyncStream[A]] = new AsyncConstantFuture(Return(AsyncStream.this))
    def hasNext = currentFuture -> async1 { current: AsyncStream[A] => !current.isEmpty }
    def next = {
      currentFuture -> async1 { current: AsyncStream[A] =>
        val result = current.head
        currentFuture = asyncTail // advance cursor
        result
      }
    }
  }*/
  
  /**
   * Convert this AsyncStream into a synchronous Stream. Calling
   * <code>tail</code> will block the current thread, while evaluation occurs
   * in a different actor.
   */
  def toStream: Stream[A] = new Stream[A] {
    
    override def isEmpty = AsyncStream.this.isEmpty
    
    def head = AsyncStream.this.head

    def tail = AsyncStream.this.asyncTail.toFunction.apply.toStream
    
    protected def addDefinedElems(buf: StringBuilder, prefix: String): StringBuilder =
      AsyncStream.this.addDefinedElems(buf, prefix)
  }

  def toList: List[A] = toStream.toList

  /**
   * Get the tail of the list and return it asynchronously, via the
   * given continuation.
   *
   * XXX: Should we require an AsyncLazyFuture?
   */
  def asyncTail: AsyncFuture[AsyncStream[A]]

  /**
   * Convert the stream to a list. The result is returned
   * asynchronously, via the given continuation.
   */
  def asyncToList: AsyncFunction0[List[A]] = {
    def toReversedList(s: AsyncStream[A], accum: List[A]): AsyncFunction0[List[A]] = {
      if (s.isEmpty) async0 { accum }
      else s.asyncTail /- async1 { tl: AsyncStream[A] => toReversedList(tl, s.head :: accum) }
    }
    toReversedList(AsyncStream.this, Nil) / async1 { list: List[A] => list.reverse }
  }

  def append[B >: A](rest: => AsyncStream[B]): AsyncStream[B] =
    if (isEmpty) rest
    else AsyncStream.cons(head, asyncTail / async1 { tail: AsyncStream[A] => tail append rest })

  def map[B](f: A => B): AsyncStream[B] =
    if (isEmpty) AsyncStream.empty
    else AsyncStream.cons(f(head), asyncTail / async1 { tail: AsyncStream[A] => tail.map(f) })

  /**
   * Perform a 'map' operation on the stream, using the given
   * AsyncFunction1. The result is returned asynchronously, via a
   * continuation.
   */
  def asyncMap[B](f: AsyncFunction1[A, B]): AsyncFunction0[AsyncStream[B]] =
    AsyncStream.fromAsyncIterator(asyncElements.asyncMap(f))

  def asyncFlatMap[B](f: AsyncFunction1[A, AsyncStream[B]]): AsyncFunction0[AsyncStream[B]] = {
    val itrF = f / async1 { (bStream: AsyncStream[B]) => bStream.asyncElements }
    val itrResult = asyncElements.asyncFlatMap(itrF)
    AsyncStream.fromAsyncIterator(itrResult)
  }
 
  def asyncFilter(p: AsyncFunction1[A, Boolean]): AsyncFunction0[AsyncStream[A]] =
    AsyncStream.fromAsyncIterator(asyncElements.asyncFilter(p))
  
  def asyncForeach(f: AsyncFunction1[A, Unit]): AsyncFunction0[Unit] =
    asyncElements.asyncForeach(f)

  def asyncFoldLeft[B](z: B)(op: AsyncFunction1[(B, A), B]): AsyncFunction0[B] =
    asyncElements.asyncFoldLeft(z)(op)

  /**
   * Concatenate two streams. The second stream is made available as
   * the result of an AsyncFunction0. The result is returned
   * asynchronously, via a continuation.
   */
  def asyncAppend[B >: A](restGetter: AsyncFunction0[AsyncStream[B]]): AsyncFunction0[AsyncStream[B]] = {
    if (isEmpty) restGetter
    else {
      asyncTail /
      async1 { tail: AsyncStream[A] => tail.asyncAppend(restGetter) } /
      async1 { appendedRestGetter: AsyncFunction0[AsyncStream[B]] => AsyncStream.cons(head, appendedRestGetter) }
    }
  }
}
