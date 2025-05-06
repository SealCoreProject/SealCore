package defs

import chisel3._
import chisel3.util._

/** 重定向包. 定義重定向信號
  */
class RedirectIO extends SealBundle {

  /** 重定向的地址
    */
  val target = Output(UInt(VAddrBits.W))

  /** 重定向的類型
    *
    * 1: branch mispredict: only need to flush frontend.
    *
    * 0: others: flush the whole pipeline
    */
  val rtype = Output(
    UInt(1.W)
  )

  /** 拉高時, 重定向包信號有效.
    */
  val valid = Output(Bool())
}
