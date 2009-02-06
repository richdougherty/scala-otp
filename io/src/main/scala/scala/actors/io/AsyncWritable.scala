package scala.actors.io

import java.nio.channels._
import java.nio.ByteBuffer
import scala.actors.controlflow._
import scala.actors.controlflow.AsyncStreamBuilder
import scala.actors.controlflow.ControlFlow._
import scala.actors.controlflow.concurrent.AsyncLock
import scala.binary.Binary
import scala.collection.immutable.Queue
import scala.collection.jcl.Conversions._

trait AsyncWritable {

  private val writeLock = new AsyncLock()

  protected def internalWrite(binary: Binary): AsyncFunction0[Unit]

  final def asyncWrite(binary: Binary): AsyncFunction0[Unit] = {
    writeLock.syn(internalWrite(binary))
  }

  final private def asyncWrite(as: AsyncStream[Binary]): AsyncFunction0[Unit] = {
    writeLock.syn(as.asyncForeach(new AsyncFunction1[Binary, Unit] {
      def apply(binary: Binary) = internalWrite(binary: Binary)
    }))
  }

}
