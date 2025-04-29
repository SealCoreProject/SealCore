package defs

import chisel3._
import chisel3.IO
import chisel3.util._
import chisel3.util.experimental.BoringUtils

import config._
import utils._

/** 參數特質, 通過混入進行傳遞
  *
  * 任何參數的設置應當被包裝到 config 中, 這個特質應該根據 Config 的信息進行推算.
  */
trait HasSealParameter {

  private val xlen = Config.getT[XLen.Value]("XLen")

  /** 機器字長
    *
    * @return
    */
  val XLEN = xlen match {
    case XLen._32 => 32
    case XLen._64 => 64
  }

  val HasDiv = true

  /** 虛擬地址位寬, 由分頁方式決定
    *
    * 儘管我將VAddrBits設置爲XLEN, 但是兩者含義不同, 且VAddrBits在多數情況下不等於XLEN.
    *
    * 例如, 在RISCV SV39 的條件下, 這個應該是 39
    *
    * @return
    */
  val VAddrBits = XLEN

  /** 物理地址位寬, 由硬件參數決定
    *
    * 我粗暴的認爲是 32 位的
    *
    * @return
    */
  val PAddrBits = 32 // PAddrBits is Physical Memory address bits

  /** 地址字長
    *
    * 這個字長是一個通用字長, 具體含義有待修正
    */
  val AddrBits = XLEN

  /** 數據寬度
    *
    * @return
    */
  val DataBits = XLEN
  val DataBytes = DataBits / 8
}

trait HasHashMethod {
  val hashMethod = Hash.XorRotateModPrime
}

/** 虛類, 將 chisel3.Module 混入特質以簡化參數傳遞
  */
abstract class SealModule
    extends Module
    with HasSealParameter
    with HasHashMethod

/** 虛類, 將 chisel3.Bundle 混入特質以簡化參數傳遞
  */
abstract class SealBundle extends Bundle with HasSealParameter
