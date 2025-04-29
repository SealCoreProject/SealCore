package module.bpu.tage

import chisel3._
import chisel3.util._
import utils._
import defs._
import module.bpu._
import module.bpu.BPUUpdate
import config.Config
import config.InstrLen
import module.bpu.basic._

/* MiniTage 預測器參數 */
trait HasMiniTageParameter {

  /* entry.Tag 位寬 */
  val entryTagWidth = 6

  /** 條目總數
    *
    * 強烈建議這個條目數是一個以2爲基的數字(32, 64, 128...)
    */
  val numEntries = 32

  /** entry下標idx 位寬 */
  val entryIdxWidth = log2Ceil(numEntries)

  /** ghr 長度增長表, 從T1開始計算 */
  val ghrLens = Seq(8, 16, 24, 32)

  /** table Idx width, 這裏加一是爲了容納 T0 */
  val tableIdxWidth = log2Ceil(ghrLens.length + 1)
}

/** 用於Mini Tage 的PCPN Info
  *
  * 對於大多數預測算法, 其在更新時需要依賴的信息不同, 因此我們沒有將它封裝爲公共服務, 而是由每個模塊自己定義
  */
class PCPNInfo extends SealBundle with HasMiniTageParameter {

  /** 用於查詢 Tage Table 的 `修正下標`
    *
    * @note
    *   - 我們的 Tage Table 在實例化時, 是從 T0 開始的.
    *   - 而Tables本身沒有將T0納入考量, 因此需要在驅動和使用時加一以補充 T0.
    *   - 由於加一的操作並不是由硬件完成, 可以保證生成出的Verilog質量較高.
    *
    * @note
    *   - 我們之所以沒有採用將 T0 直接納入TageTable模塊, 並通過判斷下標的方式進行不同的實例化,
    *   - 是因爲T0的邏輯並不是TageTable的通用邏輯, 併入TageTable生成邏輯會造成代碼功能混亂.
    */
  val tableIdx = Output(UInt(tableIdxWidth.W))

  /** 用於查詢某一個Table內的條目的下標.
    *
    * @note
    *   我們將basePred的長度與TageTable保持一致,就是爲了節省這個下標的位寬.
    */
  val entryIdx = Output(UInt(entryIdxWidth.W))

  /** 用於判斷某一個Table內的Tag, 這樣可以進一步檢查是否是同一個Table.
    */
  val entryTag = Output(UInt(entryTagWidth.W))
}

/** 更新包
  */
class MiniTageUpdateIO extends SealBundle with HasMiniTageParameter {
  val pcpn = new PCPNInfo()
  val bpu = new BPUUpdate()
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
  *   Marina Zhang <inchinaxiaofeng@gmail.com>
  *
  * @since 1.0.0
  */
class MiniTage extends SealModule with HasMiniTageParameter {
  val io = IO(new Bundle {
    // 用於預測的PC
    val pc = Input(UInt(VAddrBits.W))
    /* 拉高代表跳轉 */
    val pred = Output(Bool())
    /* 對於任何一個預測算法,在更新時能得到需要被更新對象的信息都是重要的 */
    val pcpn = new PCPNInfo()

    /* 用於更新 */
    val update = Flipped(Valid(new MiniTageUpdateIO))
  })

  // ==== 模塊實例化 ====
  // Global branch History Reg
  val ghr = Module(new GHR(ghrLens.last))
  // base predictor: 一個簡單的Bimodal
  // 理論上, 採用 GHR 最大長度作爲Bimodal預測表長度, 可以全面捕捉整個GHR的信息用於預測.
  // 但是實際上作爲一個可能非常大的GHR, 將整個GHR歷史都用作Idx不現實,
  // 因此我們將Bimodal的大小與TageTable保持一致, 並僅僅訪問最低的幾位(放棄長歷史)
  // 不能用PC進行查詢, 如果採用PC作Idx, 相當於要做LHR.
  val basePred = Module(new Bimodal(numEntries))
  basePred.io.idx := ghr.io.out(entryIdxWidth - 1, 0)
  val tageTables =
    ghrLens.map(_ => Module(new TageTable(entryTagWidth, numEntries)))
  // 基於當前情況, 計算每一個Table的 entry idx 和 tag, 並行訪問
  val entryIdxs = ghrLens.map(l =>
    Hash(
      io.pc(VAddrBits - 1, 2),
      ghr.io.out(l - 1, 0),
      Hash.XorRotateModPrime,
      entryIdxWidth
    )
  )
  val entryTags = ghrLens.map(_ =>
    Hash(
      io.pc(VAddrBits - 1, 2),
      ghr.io.out,
      Hash.Xor,
      entryTagWidth
    )
  )
  val tablesRev = tageTables.reverse
  val tablesIdxRev = tageTables.zipWithIndex.reverse

  // ==== 預測邏輯  ====
  // === 命中順位計算 ===
  // 將 basePred 加到最後, 可以最大化節省面積與時序, 保證在最壞情況下也能正常運作
  io.pred := PriorityMux(
    tablesRev.map(t =>
      (t.io.pred.valid, t.io.pred.bits)
    ) :+ (true.B, basePred.io.pred)
  )

  // 對預測功能需要的接口進行驅動
  for ((table, idx) <- tageTables.zipWithIndex) {
    table.io.idx := entryIdxs(idx)
    table.io.tag := entryTags(idx)
  }

  // === 賦值更新邏輯的依賴信息 ===
  // 將更新需要的信息向後傳遞
  // 需要注意的是, 因爲TageTables中下標爲0的實際上是T1, 因此這裏需要傳遞 `修正下標`
  io.pcpn.tableIdx := PriorityMux(
    tablesIdxRev.map(t => (t._1.io.pred.valid, (t._2 + 1).U)) :+ (true.B, 0.U)
  )
  io.pcpn.entryIdx := PriorityMux(
    tablesIdxRev.map(t =>
      (t._1.io.pred.valid, entryIdxs(t._2))
    ) :+ (true.B, ghr.io.out(entryIdxWidth - 1, 0))
  )
  io.pcpn.entryTag := PriorityMux(
    tablesIdxRev.map(t =>
      (t._1.io.pred.valid, entryTags(t._2))
    ) :+ (true.B, 0.U)
  )

  // ==== 更新邏輯 ====
  // Update. 前綴u代表Update
  val upcpn = io.update.bits.pcpn
  val utaken = io.update.bits.bpu.actualTaken
  val uwrong = io.update.bits.bpu.isMissPredict

  // === GHR 更新 ===
  ghr.io.taken.valid := io.update.valid
  ghr.io.taken.bits := utaken

  // === base predictor 更新 ===
  basePred.io.update.valid := io.update.valid
  basePred.io.update.bits.idx := upcpn.entryIdx
  basePred.io.update.bits.taken := utaken

  // === 獲得 分配候選 向量 ===
  val allocCandidates = tageTables.zipWithIndex.map(t =>
    ((t._2 + 1).U > upcpn.tableIdx) && t._1.io.isAllocatable
  )
  val winner = PriorityEncoderOH(allocCandidates)

  // === 前遞信號驅動 ===
  for ((table, idx) <- tageTables.zipWithIndex) {
    table.io.update.idx := upcpn.entryIdx
    table.io.update.tag := upcpn.entryTag
    table.io.update.isTaken := utaken
    table.io.update.isWrong := uwrong

    // 只有Tag 相等的时候, 才能进行更新(认为这个才是正确的)
    // 需要注意的是, 我們要對idx+1, 因爲下標爲0實際上是T0
    table.io.update.doTune := io.update.valid && (idx + 1).U === upcpn.tableIdx
  }

  // === 預測錯誤的更新 ===
  // 如果tableIdx === ghrLens.length, 其實將會沒有候選人, 因此不需要額外判斷
  for ((table, idx) <- tageTables.zipWithIndex) {
    when(uwrong && io.update.valid) {
      when(PopCount(winner) =/= 0.U) { // 有可分配的
        table.io.update.doAlloc := winner(idx)
        table.io.update.doAging := false.B
      }.otherwise { // 沒有 Allocatable, 做 u aging
        // 涉及到與upcpn的交互, 修正下標
        table.io.update.doAlloc := false.B
        table.io.update.doAging := (idx + 1).U > upcpn.entryIdx
      }
    }.otherwise { // 不需要更新就保持緘默
      table.io.update.doAlloc := false.B
      table.io.update.doAging := false.B
    }
  }
}
