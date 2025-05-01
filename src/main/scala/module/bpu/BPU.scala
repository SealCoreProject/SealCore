package module.bpu

import chisel3._
import chisel3.util._
import utils._
import defs._
import module.bpu.tage.MiniTage
import module.bpu.tage.MiniTagePred

/** 分支預測單元更新包.
  *
  * 定義了用於更新分支預測單元信息的格式.
  */
class BTBUpdate extends SealBundle {

  /** 更新時需要提供 PC
    */
  val pc = Output(UInt(VAddrBits.W))

  /** 表示是否預測錯誤
    *
    * 之所以將這個信號與Valid分開設計, 恰恰在於幾乎所有的分支預測都需要記錄過去歷史.
    *
    * 因此, valid 信號拉高代表更新歷史, 而歷史中是否預測錯誤交個這個信號提供.
    */
  val isMissPredict = Output(Bool())

  /** 實際的跳轉目標
    */
  val actualTarget = Output(UInt(VAddrBits.W))

  /** 實際的跳轉方向(跳轉或不跳轉)
    */
  val actualTaken = Output(Bool())

  /** 寫入BTB的類型
    */
  val btbType = Output(BTBtype())
}

class BPUIO extends SealBundle {
  /* 當前取指 PC */
  val pc = Input(UInt(VAddrBits.W))
  /* BTB 類型 */
  val btbType = Input(BTBtype())
  /* 預測更新信息 */
  val update = Input(Valid(new BTBUpdate))
  /* 方向預測, 只對Btype有效 */
  val predition = Output(Bool())
}

trait HasBPUParameter {
  val ghrLen = 32
}

class BPU_embedded extends SealModule with HasBPUParameter {
  val io = IO(new BPUIO)

  val miniTage = Module(new MiniTagePred())

  // ===== 預測邏輯(只針對 BTBtype.B) =====
}
