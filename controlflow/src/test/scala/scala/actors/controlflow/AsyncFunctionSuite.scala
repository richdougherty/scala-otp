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
class AsyncFunctionSuite extends TestNGSuite with Checkers {

  val addOne: AsyncFunction1[Int, Int] = async1 { (_: Int) + 1 }
  val double: AsyncFunction1[Int, Int] = { x: Int => x * 2 }.toAsyncFunction
  val two: AsyncFunction0[Int] = Return(2).toAsyncFunction

  @Test
  def testAsyncFunction1 = asyncTest(10000) {
    assert(addOne.toFunction.apply(2) == 3)
    assert(double.toFunction.apply(2) == 4)
    assert((addOne andThen double).toFunction.apply(2) == 6)
    assert((double andThen addOne).toFunction.apply(2) == 5)
    assert((addOne compose double).toFunction.apply(2) == 5)
    assert((double compose addOne).toFunction.apply(2) == 6)
  }

  @Test
  def testAsyncFunction0 = asyncTest(10000) {
    assert(two.toFunction.apply == 2)
    assert((two andThen addOne).toFunction.apply == 3)
    assert((two andThen double).toFunction.apply == 4)
    assert((two andThen addOne andThen double).toFunction.apply == 6)
    assert((two andThen addOne andThen double).toFunction.apply == 6)
    assert((two andThen double andThen addOne).toFunction.apply == 5)
    assert((two andThen (addOne compose double)).toFunction.apply == 5)
    assert((two andThen (double compose addOne)).toFunction.apply == 6)
  }

  @Test
  def testForSyntax = asyncTest(10000) {
    val seq = for (x <- async0 { 1 + 2 };
                   y <- async0 { x * 2 };
                   z <- async0 { y + x })
              yield { z }
    assert(seq.toFunction.apply == 9)
  }

  @Test
  def testForSyntax2 = asyncTest(10000) {
    val seq = for (_ <- async0 { println("A") };
                   _ <- asleep(666);
                   _ <- async0 { println("B") })
              yield ()
    seq.toFunction.apply
  }

}
