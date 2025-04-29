package module.bpu.basic

import chisel3._
import chisel3.util._
import utils._
import defs._

/** Bimodal 預測器
  *
  * 非常簡單的一個預測器, 一個分支預測的單元
  *
  * @param nEntries
  */
class Bimodal(val nEntries: Int = 512) extends Module {
  val idxBits = log2Ceil(nEntries)

  val io = IO(new Bundle {
    val idx = Input(UInt(idxBits.W))
    val pred = Output(Bool())

    val update = Input(Valid(new Bundle {
      val taken = Bool()
      val idx = UInt(idxBits.W)
    }))
  })

  val table = RegInit(VecInit(Seq.fill(nEntries)(1.U(2.W)))) // 01 弱不跳

  val counter = table(io.idx)

  io.pred := counter(1) // 高位作為預測（簡單粗暴）

  // 用於更新
  val ctr = table(io.update.bits.idx)
  when(io.update.valid) {
    ctr := Mux(
      io.update.bits.taken,
      Mux(ctr =/= 3.U, ctr + 1.U, ctr),
      Mux(ctr =/= 0.U, ctr - 1.U, ctr)
    )
  }
}
