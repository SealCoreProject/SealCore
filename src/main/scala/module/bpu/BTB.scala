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

/** 分支預測單元更新包.
  *
  * 定義了用於更新分支預測單元信息的格式.
  */
class BTBUpdate extends SealBundle {

  /** 更新時需要提供 PC
    */
  val pc = Output(UInt(VAddrBits.W))

  /** 表示是否預測錯誤, 当預測方向或預測目標錯誤時, 需要拉高.
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

  /** 當前是否爲函數調用.
    *
    * 我們不將Call設計爲BTBtype.C類型, 是因爲C類型在LoongArch, MIPS, RISCV中都是J類型, 沒有必要進行額外存儲,
    * 只需要進行額外判斷就好.
    */
  val call = Output(Bool())
}

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

  /** Ret 類型, 函數調用
    *
    * @return
    */
  def R = "b11".U // return

  def apply() = UInt(2.W)
}

/** 表示地址的数据结构，并定义了一些方法辅助操作和获取地址字段的值。
  */
class TableAddr(val idxBits: Int) extends SealBundle {
  // 标签字段位宽
  def tagBits = VAddrBits - PadLEN - idxBits

  val tag = UInt(tagBits.W) // 标签
  val idx = UInt(idxBits.W) // 索引
  val pad = UInt(PadLEN.W) // 填充

  def fromUInt(x: UInt) = x.asTypeOf(UInt(VAddrBits.W)).asTypeOf(this)
  def getTag(x: UInt) = fromUInt(x).tag
  def getIdx(x: UInt) = fromUInt(x).idx
}
