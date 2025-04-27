package module.bpu

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

    // val update = Input(Bool())
    // val taken = Input(Bool())
  })

  val table = RegInit(VecInit(Seq.fill(nEntries)(1.U(2.W)))) // 01 弱不跳

  val counter = table(io.idx)

  io.pred := counter(1) // 高位作為預測（簡單粗暴）

  def update(taken: Bool, idx: UInt): Unit = {
    val ctr = this.table(idx)

    ctr := Mux(
      taken,
      Mux(ctr =/= 3.U, ctr + 1.U, ctr),
      Mux(ctr =/= 0.U, ctr - 1.U, ctr)
    )
  }

  // when(io.update) {
  //   when(io.taken && counter =/= 3.U) {
  //     counter := counter + 1.U
  //   }.elsewhen(!io.taken && counter =/= 0.U) {
  //     counter := counter - 1.U
  //   }
  // }
}
