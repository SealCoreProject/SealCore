package module.bpu

import chisel3._
import chisel3.util._
import utils._
import defs._

class GHR(val length: Int) extends Module {
  val io = IO(new Bundle {
    val updateValid = Input(Bool())
    val taken = Input(Bool())
    val current = Output(UInt(length.W))
  })

  val history = RegInit(0.U(length.W))

  when(io.updateValid) {
    history := Cat(history(length - 2, 0), io.taken) // 左移加入新分支結果
  }

  io.current := history
}
