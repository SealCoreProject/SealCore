package module.bpu.tage

import chisel3._
import chisel3.util._
import utils._
import defs._

/** MiniTage 預測器參數
  */
trait HasMiniTageParameter {

  /** TAGE表數量
    */
  val nTables = 4

  /** 對應每個表的 global history 長度
    *
    * 第一個表可以不進行
    */
  val historyLengths = Seq(0, 4, 8, 16)

  /** Tag 位寬
    *
    * 在這裏, Tag 僅僅由 PC 組成, 可以降低 不同 PC 間的 Alias 發生的概率, 但是可能造成同一個 PC 不同歷史的 Alias 概率
    */
  val tagWidth = 8

  /** 條目總數
    *
    * 強烈建議這個條目數是一個以2爲基的數字(32, 64, 128...)
    */
  val numEntries = 32

}

/** MiniTage predictor
  *
  * 迷你Tage預測器
  *
  * @note
  *   在當前設計下, 具有這樣的特點:
  *   - 通過 PC 與 GHR 哈兮獲得Index;
  *   - Tag 僅僅由 PC 組成.
  *
  * @note
  *   - 支持:
  *     - 一個 base predictor（如 2-bit bimodal predictor）
  *     - 4個 TAGE entry table（固定 history length，簡單 tag 匹配）
  *   - 不支持:
  *     - 多層次歷史
  *     - 複雜替換策略、壓縮器
  *     - U-bit aging 和 alternate prediction
  *   - 特性:
  *     - Tag 固定, Tag 不含 GHR
  *     - Index 由 PC 與 GHR Hash 得到.
  *
  * @version 0.1.0
  *
  * @author
  *   Marina Zhang
  *
  * @email
  *   inchinaxiaofeng@gmail.com
  *
  * @since 0.1.0
  */
class MiniTage extends SealModule with HasMiniTageParameter {
  val io = IO(new Bundle {
    val pc = Input(UInt(VAddrBits.W))
    val ghistory = Input(UInt(32.W)) // 假設global history存成32-bit
    val predict = Output(Bool())
  })

  // base predictor: 一個簡單的Bimodal
  val bimodal = RegInit(VecInit(Seq.fill(128)(0.S(2.W))))
  val pcIdx = io.pc(6, 0)
  val basePred = bimodal(pcIdx) >= 0.S

  // tage tables
  val tables = Seq.tabulate(nTables) { i =>
    val idxWidth = log2Ceil(numEntries)
    val tage = Module(new TageTable(idxWidth, tagWidth, numEntries))
    val idx = (io.pc ^ (io.ghistory >> historyLengths(i)))(idxWidth - 1, 0)
    val tag = (io.pc >> 2)(tagWidth - 1, 0)

    tage.io.index := idx
    tage.io.tag := tag
    tage
  }

  // selector: 找到最先 match 的 prediction
  val hits = tables.map(_.io.prediction.valid)
  val preds = tables.map(_.io.prediction.bits)

  val found = Wire(Bool())
  val result = Wire(Bool())
  found := false.B
  result := basePred

  for (i <- (nTables - 1) to 0 by -1) {
    when(hits(i) && !found) {
      result := preds(i)
      found := true.B
    }
  }

  io.predict := result
}
