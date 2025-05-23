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
  *   - Empty policy: None
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
  *   - 这个RAS中并不反应栈空的情況(不進行Stall).
  *   - 在推測執行階段中PUSH造成覆蓋的情況, 沒有進行處理.
  *
  * @note
  *   - 我們假設在任何一個時刻, 訪問到的都是馬上可以Ret的值.
  *
  * @note
  *   - 這個MiniRAS不提供空棧掛起功能.
  *
  * @note
  *   - RG 全部由 Reg 组成, 同步写异步读.
  *   - s 提供一个推测快照, 不提供空栈挂起.
  *   - u 一个周期内.
  */
class RGsuRAS(depth: Int = 16) extends SealModule {
  implicit val moduleName: String = this.name
  val maxDepthIdx = depth - 1
  val idxWidth = log2Ceil(depth)

  val io = IO(new Bundle {
    val push = Input(Valid(UInt(VAddrBits.W)))
    val pop = Input(Bool())
    val commit = Input(Bool())
    val rollback = Input(Bool())

    val out = Output(UInt(VAddrBits.W)) // predicted return address
  })

  // Main stack
  val stack = Reg(Vec(depth, UInt(VAddrBits.W)))
  val sp = RegInit(0.U(idxWidth.W))
  // 指向PUSH時存入的
  val nsp = Mux(sp === maxDepthIdx.U, 0.U, sp + 1.U)

  // For speculative support
  val spCheckpoint = Reg(UInt(idxWidth.W))
  val countCheckpoint = Reg(UInt(idxWidth.W))

  // === Speculative rollback ===
  when(io.rollback) {
    sp := spCheckpoint
  }.elsewhen(io.commit) {
    spCheckpoint := sp
  }

  // === Push on call ===
  when(io.push.valid) {
    stack(nsp) := io.push.bits
    sp := nsp
  }

  when(io.pop) {
    sp := Mux(sp === 0.U, maxDepthIdx.U, sp - 1.U)
  }

  // === Outputs ===
  io.out := stack(sp)

  // === Log ===
  for ((entry, i) <- stack.zipWithIndex) {
    Trace("Stack(%d) %x\n", i.U, entry)
  }

  Debug(io.push.valid, "PUSH addr 0x%x to NSP %x\n", io.push.bits, nsp)
  Debug(io.pop, "POP addr 0x%x from SP %x\n", stack(sp), sp)
  Debug(io.commit, "Commit SP %x\n", sp)
  Debug(io.rollback, "RollBack SP %x\n", sp)
}
