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
package module.bpu.ras

import chisel3._
import chisel3.util._
import chisel3.internal.checkConnect
import utils.Trace
import utils.Debug
import defs._

/** Return Address Stack (RAS) for call/ret prediction.
  *
  *   - Full policy: Overwrite the oldest entry
  *   - Empty policy: Raise `stall` signal
  *   - Supports speculative push/pop with commit & rollback with one snapshot.
  *
  * RAS本身預測應當是絕對準確的, 帶來RAS的預測錯誤有以下幾點:
  *   - RAS表填滿溢出後缺失.
  *   - RAS在推測執行的情況下, 被錯誤的壓入或彈出.
  *     - 最常見的就是當一個分支預測給出錯誤預測後, 在重定向前, 馬上接續一個函數調用或返回.
  *     - 常常在有多發射架構, 或後端緩衝區隊列的情況下發生.
  *     - 後端計算重定向後, RAS不做處理的情況下, 將會造成整個表內現有的每一個值都將不能準確給出.
  *     - 因此, 需要在推測執行的情況下, 具有推測錯誤後回退的可能性.
  *
  * @note
  *   - 這個RAS中只能保存一個快照, 不建議應用到流水深度較大的芯片中.
  *   - 在推測執行階段中PUSH造成覆蓋的情況, 沒有進行處理.
  *
  * @note
  *   - 當在空棧的情況下, 會拉高Stall信號, 以指示外部停止動作.
  *   - 當RAS空的情況下, 幾乎沒有可能做出正確預測.
  *   - 在極大概率預測失敗的時候, 會造成TLB和MemCache的不必要開銷, 因此可以發送Stall信號以阻止取指動作.
  *   - 當後端計算出缺失地址後, 將Pop拉低
  *
  * @note
  *   - 設計中, 棧指針永遠指向有效值, 因此判斷出一個Ret類型的時候, 可以直接獲得.
  *   - 然後在同一個週期內, 將Pop信號拉低, 下一個週期的時候指針將會移動.
  */
class RGmuRAS(depth: Int = 16) extends SealModule {
  implicit val moduleName: String = this.name
  val maxDepthIdx = depth - 1
  val idxWidth = log2Ceil(depth)

  val io = IO(new Bundle {
    val push = Input(Valid(UInt(VAddrBits.W)))
    val pop = Input(Bool())
    val commit = Input(Bool())
    val rollback = Input(Bool())

    val out = Output(UInt(VAddrBits.W)) // predicted return address
    val stall = Output(Bool()) // if stack is empty during ret
  })

  // Main stack
  val stack = Reg(Vec(depth, UInt(VAddrBits.W)))
  val sp = RegInit(0.U(idxWidth.W))
  // 指向PUSH時存入的
  val nsp = Mux(sp === maxDepthIdx.U, 0.U, sp + 1.U)
  // 有效數據計數
  val count = RegInit(0.U(idxWidth.W))
  val isEmpty = count === 0.U

  // For speculative support
  val spCheckpoint = Reg(UInt(idxWidth.W))
  val countCheckpoint = Reg(UInt(idxWidth.W))

  // === Speculative rollback ===
  when(io.rollback) {
    sp := spCheckpoint
    count := countCheckpoint
  }.elsewhen(io.commit) {
    spCheckpoint := sp
    countCheckpoint := count
  }

  // === Push on call ===
  when(io.push.valid) {
    stack(nsp) := io.push.bits
    sp := nsp
    count := Mux(count === maxDepthIdx.U, count, count + 1.U)
  }

  // === Pop on ret ===
  when(io.pop) {
    count := Mux(count === 0.U, count, count - 1.U)
    when(!isEmpty) {
      sp := Mux(sp === 0.U, maxDepthIdx.U, sp - 1.U)
    }
  }

  // === Outputs ===
  io.out := stack(sp)
  io.stall := isEmpty

  // === Log ===
  for ((entry, i) <- stack.zipWithIndex) {
    Trace("Stack(%d) %x\n", i.U, entry)
  }
  Trace("sp %x count %x\n", sp, count)

  Debug(io.push.valid, "PUSH addr 0x%x to SP %x\n", io.push.bits, sp)
  Debug(io.pop, "POP addr 0x%x from SP %x\n", stack(sp), sp)
  Debug(io.commit, "Commit SP %x count %x\n", sp, count)
  Debug(io.rollback, "RollBack SP %x count %x\n", sp, count)
}
