package module.bpu

import chisel3._
import chisel3.util._
import utils._
import defs._

/** BTB 類型
  */
object BTBtype {

  /** Branch 類型, 表現爲轉移方向不確定, 地址確定
    */
  def B = "b00".U

  /** Jump 類型, 表現爲轉移方向確定, 地址確定
    */
  def J = "b01".U

  /** Indirect 類型, 表現爲轉移方向確定, 地址不確定
    *
    * @return
    */
  def I = "b10".U

  /** CallRet 類型, 函數調用
    *
    * @return
    */
  def R = "b11".U // return

  def apply() = UInt(2.W)
}

/** 分支預測單元更新包.
  *
  * 定義了用於更新分支預測單元信息的格式.
  */
class BPUUpdate extends SealBundle {

  /** 拉高此信號, 更新有效
    */
  val valid = Output(Bool())

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
