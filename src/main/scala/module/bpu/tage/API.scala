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
package module.bpu.tage

import chisel3._
import chisel3.util._
import utils._
import defs._
import module.bpu.tage.MiniTage
import module.bpu.BTBUpdate

class MiniTagePredIO extends SealBundle {
  val in = new SealBundle {
    val pc = Input(UInt(VAddrBits.W)) // 當前取指PC
    val update = Flipped(Valid(new MiniTageUpdateIO))
    val pcpn = Flipped(new PCPNInfo)
  }

  val out = new SealBundle {
    val pcpn = new PCPNInfo
    val pred = Output(Bool()) // 方向預測(僅對B type)
  }
}

class MiniTagePred extends SealModule {
  val io = IO(new MiniTagePredIO)

  // ==== 模塊實例化 ====
  val miniTage = Module(new MiniTage())

  // ==== 預測邏輯(只針對 BTBtype.B) ====
  miniTage.io.pc := io.in.pc
  io.out.pcpn := miniTage.io.pcpn
  io.out.pred := miniTage.io.pred

  // ==== 更新邏輯 ====
  miniTage.io.update := io.in.update
}
