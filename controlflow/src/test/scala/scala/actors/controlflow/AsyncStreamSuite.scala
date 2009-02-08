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
 * Tests for AsyncStream.
 *
 * @author <a href="http://www.richdougherty.com/">Rich Dougherty</a>
 */
class AsyncStreamSuite extends TestNGSuite with Checkers {

  def delayedRange(from: Int, until: Int, delay: Long) = {
    def tailFrom(i: Int): AsyncStream[Int] = {
      if (i < until) {
        Thread.sleep(delay)
        AsyncStream.cons(i, { () => tailFrom(i+1) }.toAsyncFunction)
      } else {
        AsyncStream.empty
      }
    }
    tailFrom(from)
  }

  val zeroAndOne = delayedRange(0, 2, 1)
  val threeAndFour = delayedRange(2, 4, 1)
  val zeroToFive = delayedRange(0, 5, 1)

  @Test
  def testToList = {
    asyncTest(10000) {
      check { (list: List[Int]) =>
        list == AsyncStream.fromList(list).toList
      }
    }
  }

  @Test
  def testAsyncToList = {
    asyncTest(10000) {
      check { (list: List[Int]) =>
        list == AsyncStream.fromList(list).asyncToList.within(1000).toFunction.apply
      }
    }
  }

  @Test
  def testAsyncAppend = {
    asyncTest(10000) {
      check { (list1: List[Int], list2: List[Int]) =>
        val as1 = AsyncStream.fromList(list1)
        val as2 = AsyncStream.fromList(list2)
        (list1 ++ list2) == as1.asyncAppend(Return(as2).toAsyncFunction).toFunction.apply.toList
      }
    }
  }

  @Test
  def testIterator = {
    asyncTest(10000) {
      check { (list: List[Int]) =>
        val as = AsyncStream.fromList(list)
        list == AsyncStream.fromAsyncIterator(as.asyncElements).within(1000).toFunction.apply.toList
      }
    }
  }

  @Test
  def testAsyncMap = {
    asyncTest(10000) {
      check { (list: List[Int]) =>
        def double(x: Int) = x * 2
        val asyncDouble = (double _).toAsyncFunction
        val as = AsyncStream.fromList(list)
        list.map(double) == as.asyncMap(asyncDouble).within(1000).toFunction.apply.toList
      }
    }
  }


  @Test
  def testAsyncFlatMap = {
    asyncTest(10000) {
      def expand(x: Int) = {
        val length = x / 4
        Stream.make(length, x).toList
      }
      val asyncExpand = async1 { x: Int => AsyncStream.fromList(expand(x)) }
      check { (list: List[Int]) =>
        val as = AsyncStream.fromList(list)
        list.flatMap(expand) == as.asyncFlatMap(asyncExpand).within(1000).toFunction.apply.toList
      }
    }
  }

  @Test
  def testAsyncFilter = {
    asyncTest(10000) {
      def even(x: Int) = (x % 2) == 0
      check { (list: List[Int]) =>
        val as = AsyncStream.fromList(list)
        list.filter(even) == as.asyncFilter(async1(even _)).within(1000).toFunction.apply.toList
      }
    }
  }

  @Test
  def testAsyncForeach = {
    asyncTest(10000) {
      def even(x: Int) = (x % 2) == 0
      check { (list: List[Int]) =>
        var listSum = 0
        list.foreach({ x: Int => listSum += x })
        val as = AsyncStream.fromList(list)
        var streamSum = 0
        (as.asyncForeach(async1 { x: Int => streamSum += x })).toFunction.apply
        listSum == streamSum
      }
    }
  }

  @Test
  def testAsyncFoldLeft = {
    asyncTest(10000) {
      val sum = { (a: Int, b: Int) => a + b }
      val asyncSum = async1 { t: (Int, Int) => t._1 + t._2 }
      check { (list: List[Int]) =>
        val as = AsyncStream.fromList(list)
        list.foldLeft(0)(sum) == as.asyncFoldLeft(0)(asyncSum).within(1000).toFunction.apply
      }
    }
  }

  @Test
  def testMap = {
    asyncTest(10000) {
      check { (list: List[Int]) =>
        def double(x: Int) = x * 2
        list.map(double) == AsyncStream.fromList(list).toStream.map(double).toList
      }
    }
  }

}
