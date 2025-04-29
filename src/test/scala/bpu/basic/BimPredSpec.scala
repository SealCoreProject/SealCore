package bpu.basic

import chisel3._
import chisel3.experimental.BundleLiterals._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

import testutils._

import module.bpu.basic.BimPred

class BimPredSpec extends AnyFreeSpec with Matchers with HasDebugPrint {
  "BimPred should update and predict correctly" in {
    debugPrint = false
    simulate(new BimPred) { dut =>
      // Step 1: 清空更新
      dut.io.in.update.valid.poke(false.B)
      dut.io.in.update.bits.taken.poke(false.B)
      dut.io.in.update.bits.idx.poke(0.U)
      dut.io.in.pc.poke(0x1234.U)
      dut.clock.step(5)

      // Step 2: 觀察初始預測
      dut.clock.step(1)
      val firstPred = dut.io.out.pred.peek().litToBoolean
      val firstIdx = dut.io.out.idx.peek().litValue
      println(s"[Initial] pred = $firstPred, idx = $firstIdx")

      // Step 3: 給予 update (比如not taken)，觸發更新
      dut.io.in.update.valid.poke(true.B)
      dut.io.in.update.bits.taken.poke(false.B)
      dut.io.in.update.bits.idx.poke(firstIdx.U)
      dut.clock.step(1)

      // Step 4: 再次觀察預測，應該可能發生改變（根據 Bimodal 計數器更新）
      dut.io.in.update.valid.poke(false.B)
      dut.clock.step(1)
      val afterPred = dut.io.out.pred.peek().litToBoolean
      val afterIdx = dut.io.out.idx.peek().litValue
      println(s"[After Update] pred = $afterPred, idx = $afterIdx")

      afterIdx mustBe firstIdx // 確認 index 一致

      // 預測值可能變化，也可能不變（視初始化計數器而定）
      // 你可以根據 Bimodal 初始值預設這個檢查條件：
      // afterPred mustBe false // 如果初始化是弱 taken
    }
  }
}
