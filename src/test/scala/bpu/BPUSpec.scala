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
package bpu

import chisel3._
import chisel3.experimental.BundleLiterals._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

import testutils._
import utils.LogUtil
import module.bpu.MiniBPU
import utils.LogLevel
import module.bpu.BTBtype
import module.bpu.tage.MiniTageUpdateIO
import chisel3.util.log2Up
import chisel3.util.Valid

class MiniBPUSpec extends AnyFreeSpec with Matchers with HasDebugPrint {
  "MiniBPU comprehensive test" in {
    // 设置日志与 debug 打印
    LogUtil.setLogLevel(LogLevel.DEBUG)
    LogUtil.setDisplay(false)
    debugPrint = false
    tracePrint = true
    simulate(new MiniBPU) { dut =>
      // helper: 发一个指令到预测单元
      def issueFetch(pc: UInt): Valid[UInt] = {
        dut.io.in.pc.valid.poke(true.B)
        dut.io.in.pc.bits.poke(pc)
        dut.clock.step(1)
        dut.io.in.pc.valid.poke(false.B)
        dut.io.out
      }

      // helper: 更新 BTB + TAGE
      def issueUpdate(
          pc: UInt,
          target: UInt,
          taken: Boolean,
          isMiss: Boolean,
          tpe: UInt,
          isCall: Bool
      ): Unit = {
        dut.io.update.valid.poke(true.B)
        // 保持PCPN状态不变
        dut.io.update.bits.pcpn.tableIdx.poke(dut.io.pcpn.tableIdx.peek())
        dut.io.update.bits.pcpn.ghr.poke(dut.io.pcpn.ghr.peek())
        // BTB 更新现场
        dut.io.update.bits.btb.pc.poke(pc)
        dut.io.update.bits.btb.actualTarget.poke(target)
        dut.io.update.bits.btb.actualTaken.poke(taken.B)
        dut.io.update.bits.btb.isMissPredict.poke(isMiss.B)
        dut.io.update.bits.btb.btbType.poke(tpe)
        dut.io.update.bits.btb.call.poke(isCall)
        dut.clock.step(1)
        dut.io.update.valid.poke(false.B)
      }

      // 生成各种指令序列
      val seqB = (0 until 200).map { i =>
        // 倘若PC在一直变化, 那没有什么预测的必要.
        // % 9 保证了 PC 有一定的变化
        // * 10 保证了每一个 PC 有一定的距离
        // * 4 保证了偏移(XLEN = 32 Bits)
        val pc = 0x1000 + ((i % 7) * 10 * 4)
        // B 指令：偶数 always taken，奇数 not taken
        ((pc.U, BTBtype.B, false), (pc + 8).U, i % 2 == 0)
      }

      val seqJ = (0 until 100).map { i =>
        val pc = 0x2000 + ((i % 2) * 4)
        // J 指令：总是 taken，目标为 pc+16
        ((pc.U, BTBtype.J, false), (pc + 16).U, true)
      }

      val rand = new scala.util.Random(42)
      val seqI = (0 until 50).map { i =>
        val pc = 0x3000 + (i * 4)
        // I 指令：非固定立即跳
        val taken = (i % 3 == 0)
        ((pc.U, BTBtype.I, false), rand.nextInt(10).asUInt, taken)
      }

      // 一共5個不同的 call/return 對
      val nParis = 5

      val seqCall = (0 until 40).map { i =>
        // 第一輪是爲了填充BTB表
        val j = i % nParis
        val pc = 0x4000 + (j * 4)
        val callPc = 0x5000 + (j * 4)
        // Call：通过 isCall 触发（总是 taken），返回地址入栈
        ((pc.U, BTBtype.J, true), callPc.U, true)
      }

      val seqRet = (0 until 40).map { i =>
        // 第一輪是爲了填充BTB表
        val j = (nParis - 1) - (i % nParis)
        // Return：isCall=false，取栈顶地址
        val pc = 0x5000 + (j * 4)
        val retPc = 0x4000 + (j * 4) + 4
        ((pc.U, BTBtype.R, false), retPc.U, true)
      }

      // 拼成一条大流水，交错一下
      val mixed = (seqB ++ seqJ ++ seqI ++ seqCall ++ seqRet)
        .sliding(1)
        .toSeq // 先按顺序
        .flatten

      // reset
      dut.io.in.pc.valid.poke(false.B)
      dut.io.update.valid.poke(false.B)
      dut.clock.step(5)

      // 总体的预测准确率
      var correct = 0
      var total = 0
      // 单独针对 branch 类型进行测试(用到了分支预测)
      var branch_correct = 0
      var branch_total = 0
      // 单独针对 Call Ret 类型进行测试(用到了RAS)
      var func_correct = 0
      var func_total = 0

      for (((fetchMeta, target, taken), idx) <- mixed.zipWithIndex) {
        val (pc, tpe, isCall) = fetchMeta
        // 1) fetch
        val out = issueFetch(pc)
        val predTaken = out.valid.peek().litToBoolean
        val predTarget = out.bits.peek().litValue
        issueUpdate(
          pc,
          target,
          taken,
          /*isMiss=*/ (!predTaken && taken) || (predTaken && !taken) || predTarget != target.litValue,
          // 选 tpe：B/J/I/R
          tpe,
          isCall.B
        )

        // 全部统计
        if (predTaken == taken && predTarget == target.litValue) {
          correct += 1
        }
        total += 1
        // 对 Branch 进行统计
        if (tpe.litValue == (BTBtype.B).litValue) {
          tprintln(
            s"Ret pred(${predTaken}, ${predTarget}) == actual(${taken}, ${target})"
          )
          if (predTaken == taken && predTarget == target.litValue)
            branch_correct += 1
          branch_total += 1
        }
        // 对 Ret 进行统计
        if (tpe.litValue == (BTBtype.R).litValue) {
          tprintln(
            s"Ret pred($predTarget) == actual ($target)"
          )
          if (predTarget == target.litValue)
            func_correct += 1
          func_total += 1
        }
      }

      val branch_acc = branch_correct.toDouble / branch_total
      val func_acc = func_correct.toDouble / func_total
      val acc = correct.toDouble / total
      println(
        s"[Mixed test] branch accuracy = $branch_correct/$branch_total = $branch_acc"
      )
      println(
        s"[Mixed test] func accuracy = $func_correct/$func_total = $func_acc"
      )
      println(
        s"[Mixed test] accuracy = $correct/$total = $acc"
      )
      // 期望：B 类型, 用例简单, 应该 0.90 的准确率
      branch_acc must be >= 0.9
      // 期望：R 类型, 用例简单, 应该 0.60 的准确率
      func_acc must be >= 0.6
      // 期望: 所有类型准确度, 至少 0.80
      acc must be >= 0.8
    }
  }
}
