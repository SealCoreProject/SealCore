package utils

import chisel3._
import chisel3.util._

object LFSR64 {
  def apply(increment: Bool = true.B): UInt = {
    val wide = 64
    val lfsr = RegInit(0x1234567887654321L.U(wide.W))
    val xor = lfsr(0) ^ lfsr(1) ^ lfsr(2) ^ lfsr(3)
    when(increment) {
      lfsr := Mux(lfsr === 0.U, 1.U, Cat(xor, lfsr(wide - 1, 1)))
    }
    lfsr
  }
}
