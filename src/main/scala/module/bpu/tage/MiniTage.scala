package module.bpu.tage

import chisel3._
import chisel3.util._
import utils._
import defs._
import module.bpu._
import module.bpu.BPUUpdate
import config.Config
import config.InstrLen

/* MiniTage 預測器參數 */
trait HasMiniTageParameter {

  /* Tag 位寬 */
  val tagWidth = 8

  /** 條目總數
    *
    * 強烈建議這個條目數是一個以2爲基的數字(32, 64, 128...)
    */
  val numEntries = 32

  val ghrLens = Seq(8, 16, 24, 32)

  val pcpnIdxWidth = log2Ceil(ghrLens.length)
}

/** */
class PCPNInfo extends SealBundle with HasMiniTageParameter {
  val tableIdx = Output(UInt(pcpnIdxWidth.W))
  val ghr = Output(UInt(ghrLens.last.W))
  val tag = Output(UInt(tagWidth.W))
}

/** MiniTage predictor
  *
  * 迷你Tage預測器. 我們在這個迷你 TAGE 預測器中簡化了更新流程.
  *
  * @note
  *   更新流程:
  *   - 若預測錯誤, 更新使用的 table（如存在 tag match）
  *   - 若沒有命中, 也可能插入一個新的 entry（視情況而定）
  *   - 每個 table 維護一個可信度(pred), 加強或減弱
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
  *     - 指令長度固定爲 32 位
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
  val idxWidth = log2Ceil(numEntries)

  val io = IO(new Bundle {
    // 用於預測的PC
    val pc = Input(UInt(VAddrBits.W))

    /* 拉高代表跳轉 */
    val pred = Output(Bool())
    /* 對於任何一個預測算法,在更新時能得到需要被更新對象的信息都是重要的 */
    val pcpn = new PCPNInfo()
  })

  // ==== 預測表 ====
  // base predictor: 一個簡單的Bimodal
  val basePred = Module(new Bimodal(numEntries))
  basePred.io.idx := io.pc(6, 2)
  // 在這裏 Tag 固定位數, 勢必導致越靠後的, alias問題越突出
  val tageTables =
    ghrLens.map(l =>
      Module(new TageTable(indexWidth = l, tagWidth, numEntries))
    )
  val ghr = Module(new GHR(ghrLens.last))

  // ===== 預測器的更新 ======
  // ==== 預測邏輯 ====
  val tagePreds = tageTables.map(_.io.pred)
  for ((table, len) <- tageTables.zip(ghrLens)) {
    table.io.index := ghr.get()(len - 1, 0)
    table.io.tag := Hash(
      io.pc,
      ghr.get()(len - 1, 0),
      hashMethod,
      tagWidth
    ) // 採用Idx+ghr進行Hash, 是希望不要被PC過於影響
  }

  // === 收集命中信息 ===
  val hits = tagePreds.map(_.valid)
  val preds = tagePreds.map(_.bits)

  // === 命中順位計算 ===
  val found = Wire(Bool())
  val result = Wire(Bool())
  found := false.B
  result := basePred.io.pred

  for (i <- (ghrLens.length - 1) to 0 by -1) {
    when(hits(i) && !found) {
      result := preds(i)
      io.pcpn.tableIdx := i.U
      found := true.B
    }
  }
  io.pcpn.ghr := ghr.get()
  io.pred := result

  def update(
      pcpn: PCPNInfo,
      update: BPUUpdate
  ): Unit = {
    val pc = update.pc
    val taken = update.actualTaken
    val isPredMiss = update.isMissPredict

    val pcpnIdx = pcpn.tableIdx

    // ===== 更新 provider entry =====
    for ((table, idx) <- tageTables.zipWithIndex) {
      when(idx.U === pcpnIdx) {
        table.update(
          idx = pcpn.ghr(ghrLens(idx) - 1, 0),
          tag = pcpn.tag,
          taken,
          isPredMiss
        )
      }
    }

    // ===== 嘗試分配更長歷史表 =====
    when(isPredMiss && pcpnIdx < (this.ghrLens.length - 1).U) {
      val allocCandidates = Wire(Vec(ghrLens.length, Bool()))

      for ((table, idx) <- tageTables.zipWithIndex) {
        if (idx > pcpnIdx.litValue.toInt) {
          allocCandidates(idx) := table.isAllocatable(
            pcpn.ghr(this.ghrLens(idx) - 1, 0)
          )
        } else {
          allocCandidates(idx) := false.B
        }
      }

      // 是否存在可分配的table
      val allocIdxOH = PriorityEncoderOH(allocCandidates)

      when(allocCandidates.asUInt.orR) { // 有可分配的
        for ((table, idx) <- tageTables.zipWithIndex) {
          when(allocIdxOH(idx)) {
            table.alloc(
              idx = pcpn.ghr(ghrLens(idx) - 1, 0),
              tag = pcpn.tag,
              taken
            )
          }
        }
      }.otherwise {
        // 沒有 Allocatable, 做 u aging
        for ((table, idx) <- tageTables.zipWithIndex) {
          if (idx > pcpnIdx.litValue.toInt) {
            table.aging(idx = pcpn.ghr(ghrLens(idx) - 1, 0))
          }
        }
      }
    }

    // base predictor 更新
    basePred.update(taken, pc(6, 2))
    this.ghr.update(taken)
  }
}
