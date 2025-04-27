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

// // 表示地址的数据结构，并定义了一些方法辅助操作和获取地址字段的值。
// class TableAddr(val idxBits: Int) extends MarCoreBundle {
//   // 填充字段位宽
//   val padLen =
//     if (Settings.get("IsRV32") || !Settings.get("EnableOutOfOrderExec")) 2
//     else 3
//   // 标签字段位宽
//   def tagBits = VAddrBits - padLen - idxBits
//
//   val tag = UInt(tagBits.W) // 标签
//   val idx = UInt(idxBits.W) // 索引
//   val pad = UInt(padLen.W) // 填充
//
//   def fromUInt(x: UInt) = x.asTypeOf(UInt(VAddrBits.W)).asTypeOf(this)
//   def getTag(x: UInt) = fromUInt(x).tag
//   def getIdx(x: UInt) = fromUInt(x).idx
// }
