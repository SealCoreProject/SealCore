package module.bpu.tage

import chisel3._
import chisel3.util._
import utils._

/** T_N表是使用PC与对应全局分支历史长度进行哈希索引的带标签表, TageEntry 定義了其每个表项的數據結構
  */
class TageEntry(val tagWidth: Int) extends Bundle {

  /** 3位的饱和计数器, 最高位表示预测跳转与否, 1表示预测跳转(taken), 0表示预测不跳转(not-taken)
    */
  val pred = UInt(3.W)

  /** PC与分支历史哈希得到的标签信息, 不同TN表的tag长度可以相同, 也可以不同.
    *   - 需要注意的是, 與MemCache不同的是, 分支預測的Tag可以不保證一定能對比出來.
    *   - 因爲錯誤匹配只會造成預測錯誤, 但是做到無錯匹配需要巨大的開銷.
    *
    * 實踐經驗可知, Alias 問題已經造成了巨大的預測錯誤開銷, 降低別名問題發生率可以有效緩解這個問題.
    *   - 在TN Table, tag 長度相同的情況下, N 越大, PC 佔比越少, Alias 問題越多, 預測準確率越低.
    *   - 在TN Table, tag 長度不同的情況下(增加位數以容納更多混入的GHR), Alias 問題不太會隨着N的增加而快速嚴重.
    */

  val tag = UInt(tagWidth.W)

  /** 2位 useful 計數器, 表示當前表項 "有用" 程度.
    *
    * 作用機理:
    *   - 保证最近“有用”的表项不会被替换掉
    *   - 维护一种近似于伪LRU的替换策略
    *   - 初始化为0，是为了保证该表项在有效的提供准确的预测结果时才可以获得长时间逗留的资格；
    *   - 而为了防止发生乒乓替换的现象，分配的优先级仲裁可以防止该现象的发生。
    */
  val u = UInt(2.W) // usefulness bit

  /** 是否預測爲 Taken (符號位爲正) */
  def isTaken: Bool = pred(2)

  /** 預測強度，取絕對值，用於調整更新權重（如 3 為最強） */
  def strength: UInt = pred(1, 0)

  /** useful计数器u还起到年龄计数器的作用，其MSB（bit-1）以及LSB（bit-0）会周期性的交替重置为0。
    *
    * 原文中的周期设置为每256K个分支指令进行一次重置操作。
    */
  def reset(msb: Bool): Unit = {
    when(msb) {
      u(1) := 0.U
    }.otherwise {
      u(0) := 0.U
    }
  }

  /** 老化
    */
  def aging: Unit = {
    u := Mux(this.isUseful, u - 1.U, u)
  }

  /** pred 增加 */
  def increasePred: Unit = {
    pred := Mux(pred =/= 7.U, pred + 1.U, pred)
  }

  /** pred 減少 */
  def decreasePred: Unit = {
    pred := Mux(pred =/= 0.U, pred - 1.U, pred)
  }

  /** useful 增加 */
  def increaseUseful: Unit = {
    u := Mux(u =/= 3.U, u + 1.U, u)
  }

  /** useful 減少 */
  def decreaseUseful: Unit = {
    u := Mux(u =/= 0.U, u - 1.U, u)
  }

  /** 新分配表项的初始化：
    *   - 根据最终的分支跳转结果设置pred计数器为：
    *     - 若最终的分支跳转结果为taken，则pred=4（3'b100），弱跳转
    *     - 若最终的分支跳转结果为not-taken，则pred=3（3'b011），弱不跳转
    *   - tag初始化为对应分支指令的PC与全局分支历史长度的标签哈希算法得到的标签值
    *   - u初始化为0（即：strong not useful，强没用）
    *     - 只有在u已經爲0的時候, 才會alloc, 所以我們不需要額外賦0
    *
    * @param isTaken
    */
  def init(isTaken: Bool, tag: UInt) = {
    this.pred := Mux(isTaken, 4.U, 3.U)
    this.tag := tag
  }

  /** 表項是否有效（實際應用中你可以額外加 valid 位，但通常靠 tag 匹配判斷） */
  def isValid(tag: UInt): Bool = this.tag === tag

  def msb: Bool = u(1)
  def lsb: Bool = u(0)

  def isUseless: Bool = !this.isUseless
  def isUseful: Bool = (msb | lsb)
}

/** TAGE Table 模塊
  */
class TageTable(val indexWidth: Int, val tagWidth: Int, val numEntries: Int)
    extends Module {
  val io = IO(new Bundle {

    /** 用於索引 Table entry, 通過GHR獲得 */
    val index = Input(UInt(indexWidth.W))

    /** 傳入 Hash(PC, GHR) 後的Tag. */
    val tag = Input(UInt(tagWidth.W))

    /** 當 Valid 拉高, 當前級 Tage 提出有效預測. */
    val pred = Output(Valid(Bool()))

    // Update & Replacement logic
    val doUpdate = Input(Bool()) // 是否執行 update
    val isCorrect = Input(Bool()) // 預測是否正確
    val isTaken = Input(Bool()) // 真實跳轉結果
    val altBetter = Input(Bool()) // 是否 altpred 與 fpred 不一致

    val doAlloc = Input(Bool()) // 是否執行替換分配
    val newPred = Input(Bool()) // 初始化分支跳轉方向

    val resetAging = Input(Bool()) // 是否觸發 aging
    val agingMSB = Input(Bool()) // 控制 aging MSB or LSB
  })

  val table = RegInit(
    VecInit(Seq.fill(numEntries)(0.U.asTypeOf(new TageEntry(tagWidth))))
  )
  val entry = table(io.index) // 通過 index 索引 entry.

  io.pred.valid := entry.isValid(io.tag) // Tag 相等, 預測有效.
  io.pred.bits := entry.isTaken // 當最高位爲1, 認爲跳轉.

  def isAllocatable(idx: UInt): Bool = {
    val entry = this.table(idx)
    entry.isUseless
  }

  /** 嘗試分配新表项
    *
    * @note
    *   之所以每次错误预测，都只会分配一个且仅此一个表项，是为了最小化一些偶发性或者与分支历史不甚相关的分支指令占据过多的表项的现象。
    *
    * @note
    *   定義爲方法, 是因爲可能某一個Clock內, 預測與分配同時發生, 需要並行
    */
  def alloc(idx: UInt, tag: UInt, taken: Bool): Unit = {
    val entry = this.table(idx)

    entry.init(taken, tag)
  }

  /** Tage Table Update
    */
  def update(
      idx: UInt,
      tag: UInt,
      taken: Bool,
      isPredMiss: Bool
  ): Unit = {
    val entry = this.table(idx)

    when(entry.isValid(tag)) {
      when(taken) {
        entry.increasePred
      }.otherwise {
        entry.decreasePred
      }
    }

    when(isPredMiss) {
      entry.decreaseUseful
    }.otherwise {
      entry.increaseUseful
    }
  }

  def aging(idx: UInt): Unit = {
    val entry = this.table(idx)

    when(!entry.isUseless) {
      entry.aging
    }
  }
}
