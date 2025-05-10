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
import module.bpu.tage.MiniTagePred
import module.bpu.tage.PCPNInfo
import module.bpu.tage.MiniTageUpdateIO
import module.bpu.ras.MEsuRAS

class NLPIO extends SealBundle {
  val in = new SealBundle {
    /* 當前取指 PC */
    val pc = Input(Valid(UInt(VAddrBits.W)))
  }
  /* 預測更新信息 */
  val update = Input(Valid(new MiniTageUpdateIO))
  /* Valid代表当前预测有效. 无效将会使用SNPC */
  val out = Valid(Output(UInt(VAddrBits.W)))
  /* 用於後續更新 */
  val pcpn = new PCPNInfo
}

trait HasNLPParameter {

  /** 在MiniBPU中, 我們暫時實現爲, 在Call發生時, 存儲的地址爲PC+Step
    */
  val pcStep = 4

  /** BTB长度
    */
  val NRbtb = 512

  /** RAS的长度
    */
  val NRras = 32
}

class NLP extends SealModule with HasNLPParameter {
  implicit val moduleName: String = this.name
  val io = IO(new NLPIO)
}
