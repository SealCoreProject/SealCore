package module.bpu.tage

import chisel3._
import chisel3.util._
import utils._
import defs._
import module.bpu._
import module.bpu.BTBUpdate
import config.Config
import config.InstrLen
import module.bpu.basic._

/* MiniTage 預測器參數 */
private[tage] trait HasMiniTageParameter {

  /* entry.Tag 位寬 */
  val entryTagWidth = 16

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
  *
  * @note
  *   - 我們僅僅傳遞GHR, 可以避免傳遞大量額外信息, 節省位寬.
  *   - 理論上而言, 每一個Table都有可能更新, 而每一個Table更新依賴的信息, 主要是 EntryIdx 和 EntryTag(由GHR計算)
  *   - 尤其是當Table數量較大時, 傳遞信息需要的流水間寄存器將會非常大. 基於此, 我們選擇了傳遞GHR.
  *   - 代價就是會在分支預測模塊的面積會更大一些.
  */
private[tage] class PCPNInfo extends SealBundle with HasMiniTageParameter {

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

  /** 傳遞當時的GHR, 用於計算當時的Idx和Tag.
    */
  val ghr = Output(UInt(ghrLens.last.W))
}

/** 更新包
  */
private[tage] class MiniTageUpdateIO
    extends SealBundle
    with HasMiniTageParameter {
  val pcpn = new PCPNInfo()
  val btb = new BTBUpdate()
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
  * @note
  *   version 記錄: 1.0.0 保證了功能性的絕對正確. Commit Log:<0c829d8>
  *
  * @version 1.0.0
  *
  * @author
  *   Marina Zhang <inchinaxiaofeng@gmail.com>
  *
  * @since 1.0.0
  */
private[tage] class MiniTage extends SealModule with HasMiniTageParameter {
  implicit val moduleName: String = this.name
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
  val entryTags = ghrLens.map(l =>
    Hash(
      io.pc(VAddrBits - 1, 2),
      ghr.io.out,
      Hash.Xor,
      entryTagWidth
    )
  )
  val tablesRev = tageTables.reverse
  val tablesIdxRev = tageTables.zipWithIndex.reverse
  val tablesIdx = tageTables.zipWithIndex

  // ==== 預測邏輯  ====
  // === 命中順位計算 ===
  // 將 basePred 加到最後, 可以最大化節省面積與時序, 保證在最壞情況下也能正常運作
  io.pred := PriorityMux(
    tablesRev.map(t =>
      (t.io.pred.valid, t.io.pred.bits)
    ) :+ (true.B, basePred.io.pred)
  )

  // 對預測功能需要的接口進行驅動
  for ((table, idx) <- tablesIdx) {
    table.io.idx := entryIdxs(idx)
    table.io.tag := entryTags(idx)
  }

  // === 賦值更新邏輯的依賴信息 ===
  // 將更新需要的信息向後傳遞
  // 需要注意的是, 因爲TageTables中下標爲0的實際上是T1, 因此這裏需要傳遞 `修正下標`
  io.pcpn.tableIdx := PriorityMux(
    tablesIdxRev.map(t => (t._1.io.pred.valid, (t._2 + 1).U)) :+ (true.B, 0.U)
  )
  io.pcpn.ghr := ghr.io.out

  // ==== 更新邏輯 ====
  // === 重命名信號 Update. 前綴u代表Update ===
  val uvalid = io.update.valid
  val upcpn = io.update.bits.pcpn
  val upc = io.update.bits.btb.pc
  val utaken = io.update.bits.btb.actualTaken
  val uwrong = io.update.bits.btb.isMissPredict

  // === 計算更新需要的Idx與Tag信息 ===
  val uEntryIdxs = ghrLens.map(l =>
    Hash(
      upc(VAddrBits - 1, 2),
      upcpn.ghr(l - 1, 0),
      Hash.XorRotateModPrime,
      entryIdxWidth
    )
  )
  val uEntryTags = ghrLens.map(l =>
    Hash(
      upc(VAddrBits - 1, 2),
      upcpn.ghr,
      Hash.Xor,
      entryTagWidth
    )
  )

  // === GHR 更新 ===
  ghr.io.taken.valid := uvalid
  ghr.io.taken.bits := utaken

  // === base predictor 更新 ===
  basePred.io.update.valid := uvalid
  basePred.io.update.bits.idx := upcpn.ghr(entryIdxWidth - 1, 0)
  basePred.io.update.bits.taken := utaken

  // === 獲得 分配候選 向量 ===
  val allocCandidates =
    tablesIdx.map(t => ((t._2 + 1).U > upcpn.tableIdx) && t._1.io.isAllocatable)
  val winner = PriorityEncoderOH(allocCandidates)

  // === 前遞信號驅動 ===
  for ((table, idx) <- tablesIdx) {
    table.io.update.idx := uEntryIdxs(idx)
    table.io.update.tag := uEntryTags(idx)
    table.io.update.isTaken := utaken
    table.io.update.isWrong := uwrong

    // 只有Tag 相等的时候, 才能进行更新(认为这个才是正确的)
    // 需要注意的是, 我們要對idx+1, 因爲下標爲0實際上是T0
    table.io.update.doTune := io.update.valid && (idx + 1).U === upcpn.tableIdx
  }

  // === 預測錯誤的更新 ===
  // 如果tableIdx === ghrLens.length, 其實將會沒有候選人, 因此不需要額外判斷
  for ((table, idx) <- tablesIdx) {
    when(uwrong && io.update.valid) {
      when(PopCount(winner) =/= 0.U) { // 有可分配的
        table.io.update.doAlloc := winner(idx)
        table.io.update.doAging := false.B
      }.otherwise { // 沒有 Allocatable, 做 u aging
        // 涉及到與upcpn的交互, 修正下標
        table.io.update.doAlloc := false.B
        table.io.update.doAging := (idx + 1).U > upcpn.tableIdx
      }
    }.otherwise { // 不需要更新就保持緘默
      table.io.update.doAlloc := false.B
      table.io.update.doAging := false.B
    }
  }

  // ==== Log 輸出 ====
  Trace(
    "[Update] AllocCandidates %b Winner %b pcpn: TableIdx %x GHR %b bpu: PredWrong %x\n",
    Cat(allocCandidates),
    Cat(winner),
    upcpn.tableIdx,
    upcpn.ghr,
    uwrong
  );
  Trace(
    "[Pred] Pred %x TableIdx %x GHR %b\n",
    io.pred,
    io.pcpn.tableIdx,
    io.pcpn.ghr
  )
}
