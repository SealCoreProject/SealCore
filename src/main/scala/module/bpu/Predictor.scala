package module.bpu

import chisel3._
import chisel3.util._
import utils._
import defs._
import module.bpu.tage.MiniTage

class PredictorIO extends SealBundle {
  val pc = Input(UInt(VAddrBits.W)) // 當前取指PC
  val prediction = Output(Bool()) // 方向預測(僅對B type)
}

trait HasPredParameter {
  val ghrLen = 32
}

class Predictor extends SealModule with HasPredParameter {
  val io = IO(new PredictorIO)

  // // ==== 模塊實例化 ====
  // val miniTage = Module(new MiniTage())

  // ==== 預測邏輯(只針對 BTBtype.B) ====
  // miniTage.io.pc := io.pc
}
