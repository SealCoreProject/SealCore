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
  *   - Full policy: Overwrite the oldest entry
  *   - Empty policy: Raise `stall` signal
  *   - Supports speculative push/pop with commit & rollback with multi-level
  *     snapshot.
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
  // TODO
}
