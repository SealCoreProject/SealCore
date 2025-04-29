package bpu

import chisel3._
import chisel3.experimental.BundleLiterals._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

import testutils._

import module.bpu.GHR

/** From within sbt use:
  * {{{
  * testOnly bpu.GHRSpec
  * }}}
  * From a terminal shell use:
  * {{{
  * sbt 'testOnly bpu.GHRSpec'
  * }}}
  * Testing from mill:
  * {{{
  * mill SealCore.test.testOnly bpu.GHRSpec
  * }}}
  */
class GHRSpec extends AnyFreeSpec with Matchers with HasDebugPrint {
  "GHR should correctly record branch history" in {
    debugPrint = false
    simulate(new GHR(16)) { dut => // 這裏用16位測試
      val rand = new scala.util.Random(42) // 42是個好數字. 固定seed，保證每次測一樣，穩定性👍
      // 模擬一個固定規律：偶數PC跳轉 (taken)，奇數PC不跳轉 (not taken)
      var expectedHistory = 0

      dut.io.taken.valid.poke(false.B)
      dut.io.taken.bits.poke(false.B)
      dut.clock.step(5) // warmup

      // 隨機地連續送入 50 次 update
      for (_ <- 0 until 50) {
        val taken = rand.nextBoolean()

        dut.io.taken.valid.poke(true.B)
        dut.io.taken.bits.poke(taken.B)

        // 手動模擬軟件版的 GHR 更新（最重要）
        expectedHistory =
          ((expectedHistory << 1) | (if (taken) 1 else 0)) & 0xffff // mask成16位

        dut.clock.step(1)

        val dutHistory = dut.io.out.peek().litValue
        dprintln(
          f"[Cycle] expect 0x$expectedHistory%04x, got 0x$dutHistory%04x, taken=$taken"
        )

        dutHistory mustBe expectedHistory.toLong // 確認硬件和軟件保持一致
      }
    }
  }
}
