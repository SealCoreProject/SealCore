/*
** 2025 May 1
**
** The author disclaims copyright to this source code.  In place of
** a legal notice, here is a blessing:
**
**    May you do good and not evil.
**    May you find forgiveness for yourself and forgive others.
**    May you share freely, never taking more than you give.
**
 */
package bpu.ras

import chisel3._
import chisel3.experimental.BundleLiterals._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import testutils._
import module.bpu.ras.SimpleRAS
import utils.{LogUtil, LogLevel}

class SimpleRASSpec extends AnyFreeSpec with Matchers with HasDebugPrint {
  "SimpleRAS should handle push, pop, commit and rollback correctly" in {
    LogUtil.setLogLevel(LogLevel.OFF)
    debugPrint = false
    simulate(new SimpleRAS(4)) { dut =>
      def push(addr: Int): Unit = {
        dut.io.push.valid.poke(true.B)
        dut.io.push.bits.poke(addr.U)
        dut.io.pop.poke(false.B)
        dut.clock.step(1)
        dut.io.push.valid.poke(false.B)
      }

      def pop(): BigInt = {
        dut.io.push.valid.poke(false.B)
        dut.io.pop.poke(true.B)
        val ret = dut.io.out.peek().litValue
        dut.clock.step(1)
        dut.io.pop.poke(false.B)
        ret
      }

      def commit(): Unit = {
        dut.io.commit.poke(true.B)
        dut.io.rollback.poke(false.B)
        dut.clock.step(1)
        dut.io.commit.poke(false.B)
      }

      def rollback(): Unit = {
        dut.io.rollback.poke(true.B)
        dut.io.commit.poke(false.B)
        dut.clock.step(1)
        dut.io.rollback.poke(false.B)
      }

      // Reset and warmup
      dut.io.push.valid.poke(false.B)
      dut.io.pop.poke(false.B)
      dut.io.commit.poke(false.B)
      dut.io.rollback.poke(false.B)
      dut.clock.step(5)

      // Push 4 addresses
      push(0x1000)
      push(0x2000)
      push(0x3000)
      push(0x4000)

      // Commit snapshot here
      commit()

      // Push extra to overwrite
      push(0x5000) // overwrites oldest (0x1000)

      // Pop and check
      pop() mustBe 0x5000
      pop() mustBe 0x4000

      // Rollback to committed state
      rollback()

      // Pop should now give 0x4000 again
      pop() mustBe 0x4000
      pop() mustBe 0x3000
      pop() mustBe 0x2000
      pop() mustBe 0x5000 // wrapped around

      // Stack now empty, next pop should stall
      dut.io.pop.poke(true.B)
      dut.clock.step(1)
      dut.io.stall.peek().litToBoolean mustBe true
    }
  }
}
