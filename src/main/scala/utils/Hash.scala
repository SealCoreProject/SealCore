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
package utils

import chisel3._
import chisel3.util._

/** Hash 邏輯
  *
  * 支援不同 Hash 策略選擇, 可搭配 PC, GHR, BTB index 等使用場景
  *
  * 支援強化 alias 抵抗的混合 hash 策略：包括 XOR、rotate、prime mod 等
  */
object Hash {
  sealed trait HashMethod

  case object Xor extends HashMethod
  case object Mix extends HashMethod
  case object XorRotateModPrime extends HashMethod
  // TODO: CRC, LFSR hashing

  /** 旋轉（rotate right）工具 */
  private def rotateRight(x: UInt, amount: Int): UInt = {
    val w = x.getWidth
    ((x >> amount.U) | (x << (w.U - amount.U)))(w - 1, 0)
  }

  /** 模擬類似 prime mod 的效果（實際不取模，使用 mask 摻雜） */
  private def pseudoPrimeMod(x: UInt, prime: Int): UInt = {
    val p = prime.U(x.getWidth.W)
    val result = (x ^ (x >> 3)) ^ p
    result
  }

  def apply(a: UInt, b: UInt, method: HashMethod, outWidth: Int): UInt = {
    val hashRaw = method match {
      case Xor => a ^ b
      case Mix => (a ^ (b << 1)) ^ (b >> 1)
      case XorRotateModPrime => {
        val xored = a ^ b
        val rotated = rotateRight(xored, 5)
        pseudoPrimeMod(rotated, 17) // 使用質數17做摻雜
      }
    }
    hashRaw(outWidth - 1, 0)
  }
}
