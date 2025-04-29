package bpu.tage

import chisel3._
import chisel3.experimental.BundleLiterals._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

import testutils._

import module.bpu.tage.TageTable

/** From within sbt use:
  * {{{
  * testOnly bpu.tage.TageTable
  * }}}
  * From a terminal shell use:
  * {{{
  * sbt 'testOnly bpu.tage.TageTable'
  * }}}
  * Testing from mill:
  * {{{
  * mill SealCore.test.testOnly bpu.tage.TageTable
  * }}}
  */
class TageTableSpec extends AnyFreeSpec with Matchers with HasDebugPrint {
  "TageTable should pass all basic functionalities" in {
    debugPrint = false
    simulate(new TageTable(tagWidth = 8, numEntries = 16)) { dut =>
      val rand = new scala.util.Random(42)

      dut.io.idx.poke(0.U)
      dut.io.tag.poke(0.U)
      dut.io.update.doTune.poke(false.B)
      dut.clock.step(5) // warm-up

      def alloc(idx: Int, tag: Int, taken: Boolean): Unit = {
        dut.io.update.idx.poke(idx.U)
        dut.io.update.tag.poke(tag.U)
        dut.io.update.isTaken.poke(taken.B)
        dut.io.update.isWrong.poke(false.B)
        dut.io.update.doTune.poke(false.B)
        dut.io.update.doAlloc.poke(true.B)
        dut.io.update.doAging.poke(false.B)
        dut.clock.step(1)
        dut.io.update.doAlloc.poke(false.B)
        dut.clock.step(1)
      }

      def tune(idx: Int, tag: Int, taken: Boolean, isMiss: Boolean): Unit = {
        dut.io.update.idx.poke(idx.U)
        dut.io.update.tag.poke(tag.U)
        dut.io.update.isTaken.poke(taken.B)
        dut.io.update.isWrong.poke(isMiss.B)
        dut.io.update.doTune.poke(true.B)
        dut.io.update.doAlloc.poke(false.B)
        dut.io.update.doAging.poke(false.B)
        dut.clock.step(1)
        dut.io.update.doTune.poke(false.B)
        dut.clock.step(1)
      }

      def aging(idx: Int): Unit = {
        dut.io.update.idx.poke(idx.U)
        dut.io.update.tag.poke(0.U) // Tag無所謂
        dut.io.update.isTaken.poke(false.B)
        dut.io.update.isWrong.poke(false.B)
        dut.io.update.doTune.poke(false.B)
        dut.io.update.doAlloc.poke(false.B)
        dut.io.update.doAging.poke(true.B)
        dut.clock.step(1)
        dut.io.update.doAlloc.poke(false.B)
        dut.clock.step(1)
      }

      // --- Test 1: Allocation ---
      dprintln("[Test] Allocation")

      val testIdx = 3
      val testTag = 0x5a
      alloc(testIdx, testTag, taken = true)

      dut.io.idx.poke(testIdx.U)
      dut.io.tag.poke(testTag.U)
      dut.clock.step(1)
      dut.io.pred.valid.peek().litToBoolean mustBe true // 應該能被找到
      dut.io.pred.valid.peek().litToBoolean mustBe true // 初始 u 爲0
      dut.io.pred.bits.peek().litToBoolean mustBe true // taken

      // --- Test 2: Update prediction counter ---
      dprintln("[Test] Update pred counter (decrease)")

      tune(testIdx, testTag, taken = false, isMiss = true) // 應該降低預測
      dut.clock.step(1)
      dut.io.idx.poke(testIdx.U)
      dut.io.tag.poke(testTag.U)
      dut.clock.step(1)
      dut.io.pred.valid.peek().litToBoolean mustBe true
      dut.io.pred.bits
        .peek()
        .litToBoolean mustBe false // 初始化時將Pred預設爲弱跳轉, 一輪Update後應該變爲弱不跳轉
      dut.io.isAllocatable
        .peek()
        .litToBoolean mustBe true // 初始 u 爲0, 且更新爲預測失敗一次, 應該保持0

      // --- Test 3: Update usefulness counter ---
      dprintln("[Test] Update useful counter (increase)")

      tune(
        testIdx,
        testTag,
        taken = true,
        isMiss = false
      ) // correct pred -> u++

      dut.clock.step(1)
      dut.io.idx.poke(testIdx.U)
      dut.io.tag.poke(testTag.U)
      dut.clock.step(1)
      dut.io.pred.valid.peek().litToBoolean mustBe true
      dut.io.pred.bits
        .peek()
        .litToBoolean mustBe true // 上一個 Pred 爲弱不跳轉, 一輪Update後應該變爲弱跳轉
      dut.io.isAllocatable
        .peek()
        .litToBoolean mustBe false // 上一個 u 爲0, 更新爲預測成功一次, 應該是1, 不可分配

      // --- Test 4: Predict Miss, usefulness decrease ---
      dprintln("[Test] Update usefulness counter (decrease on miss)")

      tune(testIdx, testTag, taken = true, isMiss = true) // pred miss -> u--

      dut.clock.step(1)
      dut.io.idx.poke(testIdx.U)
      dut.io.tag.poke(testTag.U)
      dut.clock.step(1)
      dut.io.pred.valid.peek().litToBoolean mustBe true
      dut.io.pred.bits
        .peek()
        .litToBoolean mustBe true // 上一個 Pred 爲弱跳轉, 一輪Update後應該變爲中跳轉(5)
      dut.io.isAllocatable
        .peek()
        .litToBoolean mustBe true // 上一個 u 爲1, 更新爲預測失敗一次, 應該是0, 可分配

      // --- Test 5: Aging ---
      dprintln("[Test] Aging")

      tune(
        testIdx,
        testTag,
        taken = false,
        isMiss = false
      ) // pred: 5 -> 4, u: 0 -> 1
      dut.clock.step(1)
      dut.io.isAllocatable
        .peek()
        .litToBoolean mustBe false // 先確保 不可分配

      aging(testIdx) // u: 1 -> 0

      dut.clock.step(1)
      dut.io.isAllocatable
        .peek()
        .litToBoolean mustBe true // 此時可分配

      // --- Test 6: Allocatable判斷 ---
      dprintln("[Test] Check allocatable")

      dut.io.idx.poke(testIdx.U)
      dut.clock.step(1)
      val isAllocatable = dut.io.isAllocatable.peek().litToBoolean mustBe true

      // --- Test 7: Invalid Tag match ---
      dprintln("[Test] Tag mismatch predict invalid")

      dut.io.idx.poke(testIdx.U)
      dut.io.tag.poke(0xab.U) // 隨便一個錯誤的tag
      dut.clock.step(1)
      dut.io.pred.valid.peek().litToBoolean mustBe false

      // --- Done ---
      dprintln("[Finish] All tests passed.")
    }
  }
}
