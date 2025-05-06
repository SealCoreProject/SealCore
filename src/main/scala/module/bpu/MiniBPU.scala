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
package module.bpu

import chisel3._
import chisel3.util._
import utils._
import defs._
import module.bpu.tage.MiniTagePred
import module.bpu.tage.PCPNInfo
import module.bpu.tage.MiniTageUpdateIO
import module.bpu.ras.MiniRAS

class MiniBPUIO extends SealBundle {
  val in = new SealBundle {
    /* 當前取指 PC */
    val pc = Input(Valid(UInt(VAddrBits.W)))
  }
  /* 預測更新信息 */
  val update = Input(Valid(new MiniTageUpdateIO))
  /* Valid代表当前预测有效. 无效将会使用SNPC */
  val out = Valid(Output(UInt(VAddrBits.W)))
  /* 用於後續更新 */
  val pcpn = new PCPNInfo
}

trait HasMiniBPUParameter {

  /** 在MiniBPU中, 我們暫時實現爲, 在Call發生時, 存儲的地址爲PC+Step
    */
  val pcStep = 4

  /** BTB长度
    */
  val NRbtb = 512

  /** RAS的长度
    */
  val NRras = 32
}

/** 嵌入式BPU模塊使用了MiniTage和MiniRAS來進行預測.
  *
  * @note
  *   使用Wavedrom打开 MiniBPUTiming 可获取时序图
  *
  * @version 1.0.0
  *
  * @since 1.0.0
  */
class MiniBPU extends SealModule with HasMiniBPUParameter {
  implicit val moduleName: String = this.name
  val io = IO(new MiniBPUIO)

  /* ==== BTB ==== */
  /* === BTB定义 === */
  val btbAddr = new TableAddr(log2Up(NRbtb))
  def btbEntry() = new Bundle {
    val tag = UInt(btbAddr.tagBits.W)
    val _type = BTBtype.apply()
    val target = UInt(VAddrBits.W)
  }

  /* === 实例化BTB === */
  val btb = Module(
    new SRAMTemplate(
      btbEntry(),
      set = NRbtb,
      shouldReset = true,
      holdRead = true,
      singlePort = true
    )
  )
  btb.io.r.req.valid := io.in.pc.valid
  btb.io.r.req.bits.setIdx := btbAddr.getIdx(io.in.pc.bits)

  /* === BTB Read === */
  val btbRead = Wire(btbEntry())
  btbRead := btb.io.r.resp.data(0)

  /* == 访问延迟与命中判断 == */
  // NOTE: Since there is one cycle latency to read SyncReadMem,
  // we should latch the input PC for one cycle
  val pcLatch = RegEnable(io.in.pc.bits, io.in.pc.valid)
  val btbHit = btbRead.tag === btbAddr.getTag(pcLatch) && RegNext(
    btb.io.r.req.ready,
    init = false.B
  )

  /* === BTB Update === */
  val btbWrite = WireInit(0.U.asTypeOf(btbEntry()))
  btbWrite.tag := btbAddr.getTag(io.update.bits.btb.pc)
  btbWrite.target := io.update.bits.btb.actualTarget
  btbWrite._type := io.update.bits.btb.btbType

  /* == 更新赋值 == */
  // NOTE: We only update BTB at a miss prediction.
  // If a miss prediction is found, the pipeline will be flushed in the next cycle.
  // Therefore it is safe to use single port SRAM implement BTB, since write request have higher priority than read request.
  // Again, since the pipeline will be flushed in the next cycle, the read request will be useless.
  btb.io.w.req.valid := io.update.bits.btb.isMissPredict && io.update.valid
  btb.io.w.req.bits.setIdx := btbAddr.getIdx(io.update.bits.btb.pc)
  btb.io.w.req.bits.data := btbWrite

  /* ==== Branch Predictor ==== */
  val pred = Module(new MiniTagePred())
  pred.io.in.pc := io.in.pc.bits
  pred.io.in.update.valid := io.update.valid && io.update.bits.btb.btbType === BTBtype.B
  pred.io.in.update.bits <> io.update.bits
  io.pcpn := pred.io.out.pcpn

  /* ==== RAS ==== */
  val ras = Module(new MiniRAS(depth = NRras))
  ras.io.commit := btbRead._type === BTBtype.B // Mux(btbRead._type === BTBtype.B, btbHit, false.B)
  ras.io.rollback := Mux(
    io.update.valid && io.update.bits.btb.btbType === BTBtype.B,
    io.update.bits.btb.isMissPredict,
    false.B
  )
  ras.io.push.valid := io.update.valid && io.update.bits.btb.call
  ras.io.push.bits := io.update.bits.btb.pc + pcStep.U
  ras.io.pop := io.update.valid && io.update.bits.btb.btbType === BTBtype.R

  /* ==== Driving out ==== */
  io.out.bits := Mux(btbRead._type === BTBtype.R, ras.io.out, btbRead.target)
  io.out.valid := btbHit && Mux(
    btbRead._type === BTBtype.B,
    pred.io.out.pred,
    true.B
  )

  /* ==== Log out ==== */
  Trace("Out valid %x target %x\n", io.out.valid, io.out.bits)
  Trace(
    "[READ] BTB Hit %x Type %x [WRITE] BTB tag %x type %x target %x\n",
    btbHit,
    btbRead._type,
    btbWrite.tag,
    btbWrite._type,
    btbWrite.target
  )
  Debug(
    io.out.valid,
    "target %x type %x branch %x\n",
    io.out.bits,
    btbRead._type,
    pred.io.out.pred
  )
  Debug(
    btbRead._type === BTBtype.R,
    "[RAS] out %x\n",
    ras.io.out
  )
}
