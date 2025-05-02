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

/** SpeculativeRAS: 支援多層快照與回滾的 Return Address Stack
  *
  * 爲了解決在推測執行期間再次發生推測執行, 打破簡單設計中的"一次錯誤, 一次回滾"的簡單假設, 我們需要多層次的快照, 通過Tag比對進行定點回退.
  *
  * @note
  *   - 不支持嵌套回滾保護
  *   - 不支持FIFO結構以進行LRU式管理
  *
  * NOTE: WIP
  */
class SpeculativeRAS(
    val StackDepth: Int = 16,
    val SnapshotDepth: Int = 8,
    val AddrWidth: Int = 32,
    val TagWidth: Int = 4
) extends Module {
//  val io = IO(new Bundle {
//    // 基本操作
//    val push = Input(Valid(UInt(AddrWidth.W)))
//    val pop = Input(Bool())
//    val top = Output(UInt(AddrWidth.W))
//    val empty = Output(Bool())
//
//    // Snapshot 控制接口
//    val pushSnapshot = Input(Valid(UInt(TagWidth.W)))
//    val rollback = Input(Valid(UInt(TagWidth.W)))
//  })
//
//  val stack = Reg(Vec(StackDepth, UInt(AddrWidth.W)))
//  val sp = RegInit(0.U(log2Ceil(StackDepth).W))
//
//  val snapshotStack = Reg(
//    Vec(SnapshotDepth, Vec(StackDepth + 1, UInt(AddrWidth.W)))
//  ) // +1 for sp
//  val snapshotValid = RegInit(VecInit(Seq.fill(SnapshotDepth)(false.B)))
//
//  // ==== 基本 Push ====
//  when(io.push.valid) {
//    stack(sp) := io.push.bits
//    sp := Mux(sp === (StackDepth - 1).U, 0.U, sp + 1.U)
//  }
//
//  // ==== Pop ====
//  when(io.pop && sp =/= 0.U) {
//    sp := sp - 1.U
//  }
//
//  io.top := Mux(sp === 0.U, 0.U, stack(sp - 1.U))
//  io.empty := (sp === 0.U)
//
//  // ==== Snapshot Push ====
//  when(io.pushSnapshot.valid) {
//    val tag = io.pushSnapshot.bits
//    when(tag < SnapshotDepth.U) {
//      for (i <- 0 until StackDepth) {
//        snapshotStack(tag)(i) := stack(i)
//      }
//      snapshotStack(tag)(StackDepth) := sp
//      snapshotValid(tag) := true.B
//    }
//  }
//
//  // ==== Rollback ====
//  when(io.rollback.valid) {
//    val tag = io.rollback.bits
//    when(tag < SnapshotDepth.U && snapshotValid(tag)) {
//      for (i <- 0 until StackDepth) {
//        stack(i) := snapshotStack(tag)(i)
//      }
//      sp := snapshotStack(tag)(StackDepth)
//    }
//  }
}
