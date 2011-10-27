package scala.actors.controlflow

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
import scala.actors.controlflow.ControlFlowTestHelper._

/**
 * Tests for control flow.
 *
 * @author <a href="http://www.richdougherty.com/">Rich Dougherty</a>
 */
class ControlFlowSuite extends TestNGSuite with Checkers {

  @Test
  def testExceptionHandler = {
    asyncTest(10000) {
      val caller = Actor.self
      class TestException extends java.lang.Exception
      try {
        asAsync0 { fc: FC[Unit] =>
          assert(Actor.self != caller) // Should be running in a different actor.
          throw new TestException
        }.toFunction.apply
        fail("Expected Exception to be thrown.")
      } catch {
        case te: TestException => () // Desired result.
        case t: Throwable => fail("Expected TestException, caught: " + t)
      }
    }
  }

  @Test
  def testCallWithCCExceptionHandler = {
    asyncTest(10000) {
      val caller = Actor.self
      class TestException extends java.lang.Exception
      try {
        async0 {
          assert(Actor.self != caller) // Should be running in a different actor.
          throw new TestException
        }.toFunction.apply
        fail("Expected Exception to be thrown.")
      } catch {
        case te: TestException => () // Desired result.
        case t: Throwable => fail("Expected TestException, caught: " + t)
      }
    }
  }

  @Test
  def testExceptionHandlerChaining = {
    asyncTest(10000) {
      val caller = Actor.self
      class TestException extends java.lang.Exception
      try {
        asAsync0 { fc: FC[Unit] =>
          import fc.implicitThr
          async0 { () } -> fc0 {
            assert(Actor.self != caller) // Should be running in a different actor.
            throw new TestException
          }
        }.toFunction.apply
        fail("Expected Exception to be thrown.")
      } catch {
        case te: TestException => () // Desired result.
        case t: Throwable => fail("Expected TestException, caught: " + t)
      }
    }
  }

  @Test
  def testAreact = asyncTest(10000) {
    val caller = Actor.self
    val adder = Actor.actor {
      println(Actor.self + ": starting adder")
      (for (
        x <- areact { case i: Int => i };
        _ <- async0 { println(Actor.self + ": Received " + x) };
        y <- areact { case i: Int => i };
        _ <- async0 { println(Actor.self + ": Received " + y) };
        _ <- async0 { caller ! (x + y) })
        yield ()) -> fc0(())(thrower)
    }
    adder ! 1
    adder ! 2
    val result = Actor.receive { case i: Int => i }
    assert(result == 3)
  }

}
