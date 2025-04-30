import circt.stage._
import chisel3._
import chisel3.stage._
import java.nio.file.{Files, Path}
import module.bpu.tage.MiniTagePred

/** mill SealCore.runMain Elaborate to run this.
  */
object Elaborate extends App {
  def top = new MiniTagePred() // 你的顶层模块

  // 开启 CIRCT + firtool
  val generator = Seq(ChiselGeneratorAnnotation(() => top))

  val firtoolOptions = Seq(
    /** 👉 禁止在 FIRRTL 中產生類似 Verilog logic [x:0] foo; 這樣在 module 中只用於本地的變量
      *
      * ✨ 意圖是： 避免生成“module 局部的中間變數”，而是強制所有東西都展開成「連線（wire）+ 賦值」，這樣對部分 EDA
      * 工具更友善，也更容易跟踪訊號流。
      *
      * 🎯 影響： 你會發現所有變數會變成 assign foo = ...;，而不是 logic foo; 然後單獨賦值。
      */
    FirtoolOption("--lowering-options=disallowLocalVariables"),
    /** 👉 禁止使用 SystemVerilog 的「打包陣列（packed array）」
      *
      * 🌰 範例：
      * {{{
      * logic [3:0][7:0] memory; // packed array，4 個 8-bit 的 word
      * }}}
      *
      * ✨ 為什麼要禁用？ 這種寫法雖然在 SystemVerilog 裡合法，但有些工具（特別是老版本或硬核合成工具）會對這種“陣列的陣列”支援不佳。
      * 這個選項會把它轉換為普通的 reg [7:0] memory[3:0]; 或展開成扁平化的結構。
      *
      * 🎯 結果： 產生的 Verilog 更像 RTL 設計師手寫的那種，也避免工具抱怨。
      */
    FirtoolOption("--lowering-options=disallowPackedArrays"),
    FirtoolOption("--split-verilog"), // 拆分成多個.sv文件
    FirtoolOption("--preserve-values=all"), // 保留中间变量名
    FirtoolOption("--strip-debug-info=0"), // 保留调试信息
    FirtoolOption("--emit-omir"), // 生成JSON
    FirtoolOption("--export-chisel-interface"), // 導出Scala Chisel interface
    FirtoolOption("--export-module-hierarchy"), // 導出模塊實例樹
    FirtoolOption("-o=build/sv-gen") // 指定输出目录
  )

  val annotations = generator ++
    Seq(CIRCTTargetAnnotation(CIRCTTarget.Verilog)) ++
    firtoolOptions

  // 使用 CIRCT ChiselStage 生成
  (new ChiselStage).execute(args, annotations)
}
