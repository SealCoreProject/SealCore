package module.bpu.basic

import chisel3._
import chisel3.util._
import utils._
import defs._
import module.bpu._

trait HasBimPredParameters {
  val numEntry = 32
  val entryIdxWidth = log2Ceil(numEntry)
}

/** Bimodal 預測器封裝
  *
  * 非常簡單的一個預測器, 一個分支預測的單元
  */
class BimPred extends SealModule with HasBimPredParameters {
  val io = IO(new Bundle {
    val in = new Bundle {
      val pc = Input(UInt(VAddrBits.W)) // 當前取指PC

      val update = Input(Valid(new Bundle {
        val taken = Output(Bool())
        val idx = Output(UInt(entryIdxWidth.W))
      }))
    }
    val out = new Bundle {
      val pred = Output(Bool())
      val idx = Output(UInt(entryIdxWidth.W))
    }
  })

  val bimobal = Module(new Bimodal(numEntry))
  val ghr = Module(new GHR(5))

  // ==== 預測邏輯 ====
  val idx = Hash(
    ghr.io.out,
    io.in.pc,
    Hash.XorRotateModPrime,
    entryIdxWidth
  )
  bimobal.io.idx := idx
  io.out.pred := bimobal.io.pred

  // ==== 信息隨行 ====
  io.out.idx := idx

  // ==== 更新邏輯 ====
  bimobal.io.update.valid := io.in.update.valid
  bimobal.io.update.bits.idx := io.in.update.bits.idx
  bimobal.io.update.bits.taken := io.in.update.bits.taken

  // ==== 歷史記錄 ====
  ghr.io.taken.valid := io.in.update.valid
  ghr.io.taken.bits := io.in.update.bits.taken
}
