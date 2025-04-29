package utils

import chisel3._
import chisel3.experimental.BundleLiterals._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import scala.collection.mutable
import testutils._

import utils.Hash

/** From within sbt use:
  * {{{
  * testOnly bpu.HashSpec
  * }}}
  * From a terminal shell use:
  * {{{
  * sbt 'testOnly bpu.HashSpec'
  * }}}
  * Testing from mill:
  * {{{
  * mill SealCore.test.testOnly bpu.HashSpec
  * }}}
  */
class HashSpec extends AnyFreeSpec with Matchers with HasDebugPrint {
  "Hash should correctly mix inputs and reduce collision rate" in {
    debugPrint = false
    simulate(new Module {
      val outWidth = 13 // 在如下參數中, 剛好大於90%
      val io = IO(new Bundle {
        val a = Input(UInt(32.W))
        val b = Input(UInt(32.W))
        val outXor = Output(UInt(outWidth.W))
        val outMix = Output(UInt(outWidth.W))
        val outXorRotateModPrime = Output(UInt(outWidth.W))
      })

      io.outXor := Hash(io.a, io.b, Hash.Xor, outWidth)
      io.outMix := Hash(io.a, io.b, Hash.Mix, outWidth)
      io.outXorRotateModPrime := Hash(
        io.a,
        io.b,
        Hash.XorRotateModPrime,
        outWidth
      )
    }) { dut =>
      val rand = new scala.util.Random(42) // 定個舒服的 seed
      val testedPairs = scala.collection.mutable.Set[(BigInt, BigInt)]()
      val hashResults = mutable.Map[String, mutable.Set[BigInt]](
        "Xor" -> mutable.Set.empty,
        "Mix" -> mutable.Set.empty,
        "XorRotateModPrime" -> mutable.Set.empty
      )

      dut.clock.step(5) // warmup

      // 送入 500組 隨機數據，測試 hash 結果
      for (_ <- 0 until 1000) {
        val a = BigInt(32, rand)
        val b = BigInt(32, rand)

        // 保證組合不重複 (防止測試有 bias)
        if (!testedPairs.contains((a, b))) {
          testedPairs.add((a, b))

          dut.io.a.poke(a.U)
          dut.io.b.poke(b.U)
          dut.clock.step(1)

          val outXor = dut.io.outXor.peek().litValue
          val outMix = dut.io.outMix.peek().litValue
          val outXorRotateModPrime = dut.io.outXorRotateModPrime.peek().litValue

          dprintln(
            f"[Cycle] a=0x$a%08x b=0x$b%08x | Xor=0x$outXor%04x Mix=0x$outMix%04x XorRotMod=0x$outXorRotateModPrime%04x"
          )

          // 收集 hash 結果做碰撞分析
          hashResults("Xor") += outXor
          hashResults("Mix") += outMix
          hashResults("XorRotateModPrime") += outXorRotateModPrime
        }
      }

      // 驗證: hash 結果的去重率必須足夠高（防止 alias 太嚴重）
      val total = testedPairs.size
      val xorUnique = hashResults("Xor").size
      val mixUnique = hashResults("Mix").size
      val xorRotUnique = hashResults("XorRotateModPrime").size

      dprintln(
        s"[Summary] total=$total xorUnique=$xorUnique mixUnique=$mixUnique xorRotUnique=$xorRotUnique"
      )

      // 這裡簡單粗暴一點判斷: 要求至少 90% 不碰撞
      xorUnique must be >= (total * 9 / 10)
      mixUnique must be >= (total * 9 / 10)
      xorRotUnique must be >= (total * 9 / 10)
    }
  }
}
