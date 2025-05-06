package utils

import chisel3._
import chisel3.util._

class SRAMBundleA(val set: Int) extends Bundle {
  val setIdx = Output(UInt(log2Up(set).W))

  def apply(setIdx: UInt) = {
    this.setIdx := setIdx
    this
  }
}

class SRAMBundleAW[T <: Data](private val gen: T, set: Int, val way: Int = 1)
    extends SRAMBundleA(set) {
  val data = Output(gen)
  val waymask = if (way > 1) Some(Output(UInt(way.W))) else None

  def apply(data: T, setIdx: UInt, waymask: UInt) = {
    this.setIdx := setIdx
    this.data := data
    this.waymask.map(_ := waymask)
    this
  }
}

class SRAMBundleR[T <: Data](private val gen: T, val way: Int = 1)
    extends Bundle {
  val data = Output(Vec(way, gen))
}

/** @example
  *   `io.r.req`:
  *   - 类型: `Decoupled(new SRAMBundleA(set))`
  *   - 含义: 读请求通道, `valid` 高时携带 `bits.setIdx` 指定读哪个「组」
  *
  * @example
  *   `io.r.resp`:
  *   - 类型: `Vec(way, gen)`
  *   - 含义: 读返回的数据向量, 长度 = `way`
  *
  * @param gen
  * @param set
  * @param way
  */
class SRAMReadBus[T <: Data](private val gen: T, val set: Int, val way: Int = 1)
    extends Bundle {

  /** 读请求通道, `valid` 高时携带 `bits.setIdx` 指定读哪个「组」.
    */
  val req = Decoupled(new SRAMBundleA(set))

  /** 读返回的数据向量, 长度 = `way`.
    */
  val resp = Flipped(new SRAMBundleR(gen, way))

  def apply(valid: Bool, setIdx: UInt) = {
    this.req.bits.apply(setIdx)
    this.req.valid := valid
    this
  }
}

/** @example
  *   `io.w.req`
  *   - 类型：Decoupled(new SRAMBundleAW(gen, set, way))
  *   - 含义：写请求通道，valid 高时携带：
  *     - bits.setIdx: 写哪个组
  *     - bits.data: 写入的数据
  *     - bits.waymask（可选）: 哪几路要写
  *
  * @param gen
  * @param set
  * @param way
  */
class SRAMWriteBus[T <: Data](
    private val gen: T,
    val set: Int,
    val way: Int = 1
) extends Bundle {

  /** 写请求通道，valid 高时携带：
    *   - bits.setIdx: 写哪个组
    *   - bits.data: 写入的数据
    *   - bits.waymask（可选）: 哪几路要写
    */
  val req = Decoupled(new SRAMBundleAW(gen, set, way))

  def apply(valid: Bool, data: T, setIdx: UInt, waymask: UInt) = {
    this.req.bits.apply(data = data, setIdx = setIdx, waymask = waymask)
    this.req.valid := valid
    this
  }
}

object FlushCmd {
  def FIXED = "b0".U // All line will be set to a fixed value
  def PLUS = "b1".U

  def apply() = UInt(1.W)
}

/** @example
  *   `io.flush.req`(如果開啓`flushSet`)
  *   - 类型: `Decoupled(new SRAMBundleFlush(gen, way))`
  *   - 含义: 一个可选的刷新接口, 用于用 `flush.data`, `flush.cmd` 覆写或清空某些组
  *
  * @param gen
  * @param way
  */
class SRAMBundleFlush[T <: Data](private val gen: T, val way: Int = 1)
    extends Bundle {
  val data = Output(gen)
  val waymask = if (way > 1) Some(Output(UInt(way.W))) else None
  val cmd = Output(FlushCmd())

  def apply(data: T, waymask: UInt, cmd: UInt) = {
    this.data := data
    this.waymask.map(_ := waymask)
    this.cmd := cmd
    this
  }
}

class SRAMFlushBus[T <: Data](private val gen: T, val way: Int = 1)
    extends Bundle {
  val req = Decoupled(new SRAMBundleFlush(gen, way))

  def apply(valid: Bool, data: T, waymask: UInt, cmd: UInt) = {
    this.req.bits.apply(data = data, waymask = waymask, cmd = cmd)
    this.req.valid := valid
    this
  }
}

class SRAMTemplate[T <: Data](
    gen: T,
    set: Int,
    way: Int = 1,
    /** 启动上电清零(或固定值)逻辑 */
    shouldReset: Boolean = false,
    /** 是否保持读——即在不出新请求时锁存上一次读结果 */
    holdRead: Boolean = false,
    /** 单口模式下读写不能同时进行 */
    singlePort: Boolean = false,
    flushSet: Boolean = false,
    flushPriority: Boolean = false
) extends Module {
  implicit val moduleName: String = this.name
  val io = IO(new Bundle {
    val r = Flipped(new SRAMReadBus(gen, set, way))
    val w = Flipped(new SRAMWriteBus(gen, set, way))
    val flush =
      if (flushSet) Some(Flipped(new SRAMFlushBus(gen, way))) else None
  })

  val wordType = UInt(gen.getWidth.W)
  val array = SyncReadMem(set, Vec(way, wordType))
  val (resetState, resetSet) = (WireInit(false.B), WireInit(0.U))

  // 用计数器，逐个刷新CacheSet
  if (shouldReset) {
    val _resetState = RegInit(true.B)
    val (_resetSet, resetFinish) = Counter(_resetState, set)
    when(resetFinish) { _resetState := false.B }

    resetState := _resetState
    resetSet := _resetSet
  }

  val flushValid =
    if (flushSet) io.flush.get.req.valid
    else false.B // Maybe Could use DontCare
  val flushCmd = if (flushSet) io.flush.get.req.bits.cmd else 0.U
  val flushData = if (flushSet) io.flush.get.req.bits.data.asUInt else 0.U
  val f_waymask =
    if (flushSet) io.flush.get.req.bits.waymask.getOrElse("b1".U) else 0.U

  val (ren, wen, fen) =
    (io.r.req.valid, io.w.req.valid || resetState, flushValid && !resetState)
  val realRen = (if (singlePort) ren && !wen else ren)

  val setIdx = Mux(resetState, resetSet, io.w.req.bits.setIdx)

  val fdataword = MuxLookup(flushCmd, io.w.req.bits.data.asUInt)(
    Seq(
      FlushCmd.FIXED -> (flushData),
      FlushCmd.PLUS -> (flushData) // 确保使用这个命令时不会发生溢出，以避免出现BUG
    )
  )
  val fdata = VecInit(Seq.fill(way)(fdataword))

  val wdataword =
    Mux(resetState, 0.U.asTypeOf(wordType), io.w.req.bits.data.asUInt)
  val w_waymask =
    Mux(resetState, Fill(way, "b1".U), io.w.req.bits.waymask.getOrElse("b1".U))
  val wdata = VecInit(Seq.fill(way)(wdataword))

//	val combData = VecInit(Seq.tabulate(way) {
//		i => Mux(w_waymask(i) && f_waymask(i),
//			Mux(flushPriority, fdata(i), wdata(i)),
//			Mux(w_waymask(i), wdata(i), fdata(i)))
//	})
  val combData = VecInit(Seq.tabulate(way) { i =>
    Mux(
      !f_waymask(i) || (w_waymask(i) && !flushPriority.asBool),
      wdata(i),
      fdata(i)
    )
  }) // K-map
  val combWaymask = f_waymask | w_waymask
  when(wen || fen) { array.write(setIdx, combData, combWaymask.asBools) }

  val rdata = (if (holdRead) ReadAndHold(array, io.r.req.bits.setIdx, realRen)
               else
                 array.read(io.r.req.bits.setIdx, realRen)).map(_.asTypeOf(gen))
  io.r.resp.data := VecInit(rdata)
  io.r.req.ready := !resetState && (if (singlePort) !wen else true.B)
  io.w.req.ready := true.B
  io.flush.foreach { flushIO =>
    flushIO.req.ready := true.B
  }
}

class SRAMTemplateWithArbiter[T <: Data](
    nRead: Int,
    gen: T,
    set: Int,
    way: Int = 1,
    /** 启动上电清零(或固定值)逻辑 */
    shouldReset: Boolean = false,
    flushSet: Boolean = false,
    flushPriority: Boolean = false
) extends Module {
  implicit val moduleName: String = this.name
  val io = IO(new Bundle {
    val r = Flipped(Vec(nRead, new SRAMReadBus(gen, set, way)))
    val w = Flipped(new SRAMWriteBus(gen, set, way))
    val flush =
      if (flushSet) Some(Flipped(new SRAMFlushBus(gen, way))) else None
  })

  val ram = Module(
    new SRAMTemplate(
      gen,
      set,
      way,
      shouldReset,
      holdRead = false,
      singlePort = true,
      flushSet = flushSet,
      flushPriority = false
    )
  )
  ram.io.w <> io.w

  val readArb = Module(new Arbiter(chiselTypeOf(io.r(0).req.bits), nRead))
  readArb.io.in <> io.r.map(_.req)
  ram.io.r.req <> readArb.io.out

  io.flush.foreach { flushIO =>
    flushIO <> ram.io.flush.get
  }

  // latch read results
  io.r.map {
    case r => {
      r.resp.data := HoldUnless(ram.io.r.resp.data, RegNext(r.req.fire))
    }
  }
}
