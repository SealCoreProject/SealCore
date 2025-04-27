package module.bpu

import chisel3._
import chisel3.util._
import utils._
import defs._

/** GHR 模組
  *
  * @param length
  *   表示 GHR 表長度
  */
class GHR(val length: Int) extends Module {
  val history = RegInit(0.U(length.W))

  /** 更新history
    *
    * @param taken
    *   跳轉情況
    */
  def update(taken: Bool): Unit = {
    this.history := Cat(this.history(length - 2, 0), taken)
  }

  /** 獲得當前history
    *
    * @return
    *   history
    */
  def get(): UInt = {
    history
  }
}
