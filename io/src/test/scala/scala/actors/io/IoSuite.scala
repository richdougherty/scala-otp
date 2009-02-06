package scala.actors.io

import org.testng.annotations.{Test, BeforeMethod}

import org.scalatest.testng.TestNGSuite
import org.scalatest.prop.Checkers
import org.scalacheck.Arbitrary
import org.scalacheck.Arbitrary._
import org.scalacheck.Gen
import org.scalacheck.Prop._
import org.scalatest._
import scala.actors.controlflow._
import scala.actors.controlflow.ControlFlow._
import scala.binary._
import java.net.InetSocketAddress
import java.nio.channels._
import java.nio.ByteBuffer

/**
 * Tests for io classes.
 *
 * @author <a href="http://www.richdougherty.com/">Rich Dougherty</a>
 */
class IoSuite extends TestNGSuite with Checkers {

  implicit def arbBinary: Arbitrary[Binary] = Arbitrary {
    for (bytes <- Arbitrary.arbitrary[Array[Byte]]) yield Binary.fromSeq(bytes)
  }

  @Test
  def testSocket = {
    val binary = Binary.fromString("Hello ") ++ Binary.fromString("world!")
    val address = new InetSocketAddress("localhost", 12345)

    val result = { fc: FC[Binary] =>
      import fc.implicitThr
      val selector = new AsyncSelector
      val ssc = ServerSocketChannel.open
      ssc.configureBlocking(false)
      val ss = ssc.socket
      ss.setReuseAddress(true)
      ss.bind(address)
      val rssc  = new AsyncServerSocketChannel(ssc, selector)
      Actor.actor {
        println("Accepting")
        rssc.asyncAccept -> fc1 { sc1: SocketChannel => 
          sc1.configureBlocking(false)
          val rsc1 = new AsyncSocketChannel(sc1, selector)
          println("Sending: " + new String(binary.toArray))
          rsc1.asyncWrite(binary) -> fc0 { println("Closing socket") ; sc1.close }
          println("Closing server socket")
          ssc.close
        }
      }
      Actor.actor {
        val sc2: SocketChannel = SocketChannel.open
        sc2.configureBlocking(false)
        val rsc2 = new AsyncSocketChannel(sc2, selector)
        println("Connecting")
        rsc2.asyncConnect(address) -> fc0 { println("Receiving") ; rsc2.asyncReadAll -> fc }
      }
      Actor.exit
    }.toFunction.apply
    println("Received: " + new String(result.toArray))
    assert(result == binary)
  }
  
}
