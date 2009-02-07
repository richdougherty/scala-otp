package scala.actors.io

import java.nio.channels._
import java.nio.ByteBuffer
import scala.actors.controlflow._
import scala.actors.controlflow.ControlFlow._
import scala.actors.controlflow.concurrent._
import scala.binary.Binary
import scala.collection.immutable.Queue
import scala.collection.jcl.Conversions._

trait AsyncReadable {

  protected def defaultReadLength: Int = 512

  @volatile
  private var readStreamFuture: AsyncFuture[AsyncStream[Binary]] =
    new AsyncLazyFuture[AsyncStream[Binary]](nextReadStream)

  private def nextReadStream: AsyncFunction0[AsyncStream[Binary]] = new AsyncFunction0[AsyncStream[Binary]] {
    def ->(fc: FC[AsyncStream[Binary]]) = {
      import fc.implicitThr
      internalRead(defaultReadLength) -> fc1 { binary: Binary =>
        if (binary.isEmpty) {
          fc.ret(AsyncStream.empty)
        } else {
          val stream = AsyncStream.cons(binary, nextReadStream)
          readStreamFuture = stream.asyncTail
          fc.ret(stream)
        }
      }
    }
  }

  // returns zero-length Binary when reaches end
  protected def internalRead(length: Int): AsyncFunction0[Binary]

  final def asyncReadStream: AsyncFuture[AsyncStream[Binary]] = readStreamFuture

  final def asyncRead: AsyncFunction0[Binary] = asyncReadLength(defaultReadLength)

  final def asyncReadLength(length: Int): AsyncFunction0[Binary] = internalRead(length)

  final def asyncReadAll = new AsyncFunction0[Binary] {
    def ->(fc: FC[Binary]) = {
      import fc.implicitThr
      val append = ((bs: (Binary, Binary)) => bs._1 ++ bs._2).toAsyncFunction
      asyncReadStream -> fc1 { as: AsyncStream[Binary] => as.asyncFoldLeft(Binary.empty)(append) -> fc }
    }
  }
}
