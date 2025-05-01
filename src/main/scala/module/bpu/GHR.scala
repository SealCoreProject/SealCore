/*
** 2025 May 1
**
** The author disclaims copyright to this source code.  In place of
** a legal notice, here is a blessing:
**
**    May you do good and not evil.
**    May you find forgiveness for yourself and forgive others.
**    May you share freely, never taking more than you give.
**
 */
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
  val io = IO(new Bundle {
    /* 獲得當前history */
    val out = Output(UInt(length.W))

    // Valid 代表更新. Bits 代表跳轉情況
    val taken = Input(Valid(Bool()))
  })

  val history = RegInit(0.U(length.W))

  // 更新history
  when(io.taken.valid) {
    history := Cat(history(length - 2, 0), io.taken.bits)
  }

  io.out := history
}
