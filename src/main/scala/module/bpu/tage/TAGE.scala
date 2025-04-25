package module.bpu.tage

import chisel3._
import chisel3.util._
import utils._
import defs._

/** T_N表是使用PC与对应全局分支历史长度进行哈希索引的带标签表, TageEntry 定義了其每个表项的數據結構
  */
class TageEntry(val tagWidth: Int) extends Bundle {

  /** 3位的有符号饱和计数器, 符号位表示预测跳转与否, 1表示预测跳转(taken), 0表示预测不跳转(not-taken)
    */
  val pred = SInt(3.W)

  /** PC与分支历史哈希得到的标签信息, 不同TN表的tag长度可以相同, 也可以不同.
    *
    * 需要注意的是, 與MemCache不同的是, 分支預測的Tag可以不保證一定能對比出來.
    *
    * 因爲錯誤匹配只會造成預測錯誤, 但是做到無錯匹配需要巨大的開銷.
    *
    * 實踐經驗可知, Alias 問題已經造成了巨大的預測錯誤開銷, 降低別名問題發生率可以有效緩解這個問題.
    *
    *   - 如果在不同 TN Table tag 長度相同的情況下, N 越小, PC 佔比越多, PC Alias 問題越少, 預測準確率越高.
    *   - 如果在不同 TN Table tag 長度不同的情況下(PC佔比固定), N 越小, 面積開銷越小, 但是 PC Alias
    *     問題不會變得更少.
    */

  val tag = UInt(tagWidth.W)

  /** 2位 useful 計數器, 表示當前表項 "有用" 程度.
    */
  val u = UInt(2.W) // usefulness bit
}

/** TAGE Table 模塊
  */
class TageTable(val indexWidth: Int, val tagWidth: Int, val numEntries: Int)
    extends Module {
  val io = IO(new Bundle {

    /** 用於索引 Table entry
      *
      * 這個索引往往通過PC 與 GHR 的哈兮獲得, 在這裏通過 哈兮前混入更多 GHR 可以緩解 GHR Alias, (但是會加劇 PC
      * Alias)
      */
    val index = Input(UInt(indexWidth.W))

    /** 傳入 Hash(Cat(PC + GHR)) 後的Tag.
      */
    val tag = Input(UInt(tagWidth.W))

    // 當 Valid 拉高, 當前級 Tage 提出有效預測.
    val prediction = Output(Valid(Bool()))
  })

  // NOTE: 這裏採用 Reg 存儲
  val table = RegInit(
    VecInit(Seq.fill(numEntries)(0.U.asTypeOf(new TageEntry(tagWidth))))
  )
  val entry = table(io.index) // 通過 index 索引 entry.

  io.prediction.valid := entry.tag === io.tag // Tag 相等, 預測有效.
  io.prediction.bits := entry.pred >= 0.S // 當最高位爲1, 認爲跳轉.
}
