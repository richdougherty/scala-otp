package scala.actors.controlflow

import scala.actors.controlflow.ControlFlow._

object AsyncIterator {
  
  val empty = new AsyncIterator[Nothing] {
    def hasNext = async0 { false }
    def next = async0 { throw new NoSuchElementException("next on empty iterator") }
  }
  
}

trait AsyncIterator[+A] {

  def hasNext: AsyncFunction0[Boolean]

  def next: AsyncFunction0[A]
  
  def asyncMap[B](f: AsyncFunction1[A, B]) = new AsyncIterator[B] {
    def hasNext = AsyncIterator.this.hasNext
    def next = AsyncIterator.this.next / f
  }

  def asyncFlatMap[B](f: AsyncFunction1[A, AsyncIterator[B]]): AsyncIterator[B] = new AsyncIterator[B] {
    private var cur: AsyncIterator[B] = AsyncIterator.empty
    private def handle[C](
      curHasNextHandler: => AsyncFunction0[C],
      origHasNextHandler: => AsyncFunction0[C],
      noNextHandler: => AsyncFunction0[C]) = new AsyncFunction0[C] {
        def ->(fc: FC[C]): Nothing = {
          import fc.implicitThr
          cur.hasNext -> fc1 { (curHasNext: Boolean) =>
            if (curHasNext) curHasNextHandler -> fc
            else {
              AsyncIterator.this.hasNext -> fc1 { (origHasNext: Boolean) =>
                if (origHasNext) {
                  AsyncIterator.this.next / f -> fc1 { (fResult: AsyncIterator[B]) =>
                    cur = fResult
                    origHasNextHandler -> fc
                  }
                } else {
                  noNextHandler -> fc
                }
              }
            }
          }
        }
      }
    def hasNext =
      handle[Boolean](
        Return(true).toAsyncFunction,
        hasNext,
        Return(false).toAsyncFunction
      )
    def next =
      handle[B](
        cur.next,
        next,
        Throw(new NoSuchElementException("next on empty iterator")).toAsyncFunction
      )
  }
  
  
  def asyncFilter(p: AsyncFunction1[A, Boolean]) = new AsyncIterator[A] {

    // XXX: Replace with AsyncLazyFuture
    private var loaded: Option[A] = None

    private def loadNext: AsyncFunction0[Unit] = new AsyncFunction0[Unit] {
      def ->(fc: FC[Unit]): Nothing = {
        loaded match {
          case s: Some[A] => fc.ret(())
          case None => {
            import fc.implicitThr
            AsyncIterator.this.hasNext -> fc1 { (origHasNext: Boolean) =>
              if (origHasNext) {
                AsyncIterator.this.next -> fc1 { (origNext: A) =>
                  p(origNext) -> fc1 { (matches: Boolean) =>
                    if (matches) {
                      loaded = Some(origNext)
                      fc.ret(())
                    } else {
                      loadNext -> fc
                    }
                  }
                }
              } else {
                // Reached end.
                fc.ret(())
              }
            }
          }
        }
      }
    }

    def hasNext = {
      loadNext / async1 { _: Unit =>
        loaded match {
          case Some(_) => true
          case None => false
        }
      }
    }
    def next = {
      loadNext / async1 { _: Unit =>
        loaded match {
          case Some(element) => {
            loaded = None
            element
          }
          case None => throw new NoSuchElementException
        }
      }
    }
  }
 
  def asyncForeach(f: AsyncFunction1[A, Unit]): AsyncFunction0[Unit] = new AsyncFunction0[Unit] {
    def ->(fc: FC[Unit]): Nothing = {
      import fc.implicitThr
      AsyncIterator.this.hasNext -> fc1 { (origHasNext: Boolean) =>
        if (origHasNext) {
          AsyncIterator.this.next -> fc1 { (a: A) =>
            f(a) -> fc0 { asyncForeach(f) -> fc }
          }
        } else fc.ret(())
      }
    }
  }

  def asyncFoldLeft[B](z: B)(op: AsyncFunction1[(B, A), B]): AsyncFunction0[B] = new AsyncFunction0[B] {
    def ->(fc: FC[B]): Nothing = {
      import fc.implicitThr
      hasNext -> fc1 { hasNextResult: Boolean =>
        if (hasNextResult) {
          next -> fc1 { nextResult: A =>
            op((z, nextResult)) -> fc1 { zNew: B =>
              asyncFoldLeft(zNew)(op) -> fc
            }
          }
        } else {
          fc.ret(z)
        }
      }
    }
  }

}
