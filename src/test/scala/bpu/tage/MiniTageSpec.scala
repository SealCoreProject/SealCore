package bpu.tage

import chisel3._
import chisel3.experimental.BundleLiterals._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

import module.bpu.tage.{MiniTage, MiniTagePred}
import testutils.HasDebugPrint

/** This is a trivial example of how to run this Specification
  *
  * From within sbt use:
  * {{{
  * testOnly bpu.tage.MiniTageSpec
  * }}}
  * From a terminal shell use:
  * {{{
  * sbt 'testOnly bpu.tage.MiniTageSpec'
  * }}}
  * Testing from mill:
  * {{{
  * mill SealCore.test.testOnly bpu.tage.MiniTageSpec
  * }}}
  */
class MiniTageSpec extends AnyFreeSpec with Matchers with HasDebugPrint {
  // "MiniTage should correctly predict branches after warmup" in {
  //   simulate(new MiniTagePred) { dut =>
  //     // ==== 測試配置 ====
  //     val numWarmup = 200 // Warmup 階段
  //     val numTest = 100 // 正式測試階段
  //     val totalCycles = numWarmup + numTest
  //     val historyPattern =
  //       (0 until totalCycles).map(i => (i % 3) != 0) // 模擬: 每逢3的倍數不跳, 其餘跳

  //     // ==== 初始化 ====
  //     dut.reset.poke(true.B)
  //     dut.clock.step()
  //     dut.reset.poke(false.B)
  //     dut.clock.step()

  //     var correctPredictions = 0
  //     var totalPredictions = 0
  //     val basePC = BigInt("80000000", 16)

  //     for (cycle <- 0 until totalCycles) {
  //       val pc = basePC + (cycle * 4)
  //       val taken = historyPattern(cycle)

  //       // ===== 讀取預測 =====
  //       dut.io.in.pc.poke(pc.U)
  //       dut.io.in.update.valid.poke(false.B) // 暫時不提供PCPN

  //       dut.clock.step()

  //       val prediction = dut.io.out.pred.peek().litToBoolean

  //       // ==== 正式測試階段: 統計準確率 ====
  //       if (cycle >= numWarmup) {
  //         totalPredictions += 1
  //         if (prediction == taken) {
  //           correctPredictions += 1
  //         }
  //       }

  //       // ===== 更新MiniTage =====
  //       dut.io.in.update.valid.poke(true.B)
  //       dut.io.in.update.bits.pc.poke(pc.U)
  //       dut.io.in.update.bits.taken.poke(taken.B)
  //       dut.io.in.update.bits.is_br.poke(true.B)
  //       dut.io.in.pcpn.valid.poke(true.B)
  //       dut.io.in.pcpn.bits := (new PCPNInfo).Lit(
  //         _.isBr -> true.B,
  //         _.isJal -> false.B,
  //         _.isCall -> false.B,
  //         _.isRet -> false.B
  //       )

  //       dut.clock.step()
  //     }

  //     val accuracy = correctPredictions.toDouble / totalPredictions
  //     println(f"MiniTage Accuracy after warmup: ${accuracy * 100}%.2f%%")

  //     // ==== 驗證：準確率大於一定閾值(例如60%) ====
  //     assert(accuracy > 0.6, "MiniTage failed to learn the pattern!")
  //   }
  // }

  "MiniTage should correctly predict only use base predictor" in {
    debugPrint = false
    simulate(new MiniTagePred()) { dut =>
      // 每一次都傳遞不同的PC, 這樣可以在預測次數少的情況下, 確保僅僅使用了BasePred.
      // 同時還能在足夠多次後, 看到Alias帶來的預測失敗.
      //
      // NOTE: 測試結果
      // 當 predNum == 50 時, 由於其他表的Idx和Tag有PC參與Hash, 因此不會有任何Table被分配後再次訪問到且Tag相同(成功預測.)
      // 此時, 預測失敗次數爲 4. 也就是只需要 4 個Cycle, 之後的所有Cycle, 在不發生 Idx 和 Tag 同時別名的情況, 就不再會預測失敗了.
      // ___
      // Idx
      // Idx = Hash(pc(highest, 2), ghr(l - 1, 0)) XorRotateModPrime
      // Tag = Hash(pc(highest, 2), ghr) Xor
      // ___
      // Seq((predNum, AliasNum)) = (500, 2), (1000, 4), (1500, 6), (3000, 12), (4000, 19), (6000, 23), (8000, 31), (10000, 39)

      // 模擬一個固定規律：偶數PC跳轉 (taken)，奇數PC不跳轉 (not taken)
      val testSeq = (0 until 10000).map(i => ((i * 4).U, (i % 2 == 0)))

      // 預測多少輪.
      val predNum = 50

      // 初始化
      dut.clock.step(5) // reset warmup
      dut.reset.poke(false.B)

      var cycles = 0
      var correct_predictions = 0
      var total_predictions = 0

      // Warm up + Pred 階段：讓 MiniTage 學習這個模式的同時進行預測
      for ((pc, taken) <- testSeq.take(predNum)) {
        // 進行預測
        dut.io.in.pc.poke(pc)
        dut.io.in.update.valid.poke(true.B)
        // 傳遞結果
        val predict = dut.io.out.pred.peek().litToBoolean
        // 獲得PCPN隨遞信息
        val pcpn_tableIdx = dut.io.out.pcpn.tableIdx.peek()
        val pcpn_entryIdx = dut.io.out.pcpn.entryIdx.peek()
        val pcpn_entryTag = dut.io.out.pcpn.entryTag.peek()

        if (predict == taken) {
          correct_predictions += 1
        }

        total_predictions += 1

        // 打印預測信息
        dprintln(
          s"pc ${pc} taken ${taken} pred ${predict} tableIdx ${pcpn_tableIdx} entryIdx ${pcpn_entryIdx}"
        )

        // 根據結果進行修正
        dut.io.in.update.valid.poke(true.B)
        dut.io.in.update.bits.bpu.pc.poke(pc)
        dut.io.in.update.bits.bpu.actualTaken.poke(taken.B)
        dut.io.in.update.bits.bpu.isMissPredict.poke((predict != taken).B)
        dut.io.in.update.bits.pcpn.tableIdx.poke(pcpn_tableIdx)
        dut.io.in.update.bits.pcpn.entryIdx.poke(pcpn_entryIdx)
        dut.io.in.update.bits.pcpn.entryTag.poke(pcpn_entryTag)

        dut.clock.step(1)
      }

      println(s"Accuracy after warmup: $correct_predictions/$total_predictions")

      // 期待：大部分預測正確
      correct_predictions.toDouble / total_predictions must be >= 0.9
    }
  }

  "MiniTage should correctly predict when there are complex jump patterns" in {
    debugPrint = false
    simulate(new MiniTagePred()) { dut =>
      // 1) 模拟 while(true) 循环中的 if 判断
      // 对于PC1，代表判断 while 是否继续，PC2 判断是否退出。PC1 总是跳转，PC2 50次不跳转后进行一次跳转
      val whileTestSeq = (0 until 1000).map { i =>
        if (i % 2 == 0) ((i * 4).U, true) // PC1 总是跳转
        else if (i % 100 == 99) ((i * 4).U, true) // PC2 跳转在每100次之后
        else ((i * 4).U, false) // 其余不跳转
      }

      // 2) 模拟 for 循环中的 switch 语句
      // 对于PC3，在100次跳转后不跳转；PC4到PC8模拟switch跳转
      val forTestSeq = (0 until 1000).map { i =>
        if (i % 4 == 0) ((i * 4).U, true) // PC3 每100次跳转一次
        else if (i % 5 == 0) ((i * 4).U, true) // PC4 跳转
        else if (i % 6 == 0) ((i * 4).U, true) // PC5 跳转
        else if (i % 7 == 0) ((i * 4).U, true) // PC6 跳转
        else if (i % 8 == 0) ((i * 4).U, true) // PC7 跳转
        else if (i % 9 == 0) ((i * 4).U, true) // PC8 跳转
        else ((i * 4).U, false) // 不跳转
      }

      // 3) 一些其他模式：模拟短时间内改变的跳转模式
      val otherTestSeq = (0 until 1000).map { i =>
        if (i % 3 == 0) ((i * 4).U, true) // 每3次跳转
        else ((i * 4).U, false) // 不跳转
      }

      // 合并所有测试序列
      val testSeq = whileTestSeq ++ forTestSeq ++ otherTestSeq

      // 模拟多少轮
      val predNum = testSeq.length

      // 初始化
      dut.clock.step(5) // reset warmup
      dut.reset.poke(false.B)

      var cycles = 0
      var correct_predictions = 0
      var total_predictions = 0
      var accuracyHistory = Seq[Double]()
      // 表使用統計：每10次記一次比例
      val tableUseCount =
        scala.collection.mutable.Map[Int, Int]().withDefaultValue(0)
      val tableProportionHistory =
        scala.collection.mutable.ArrayBuffer[Map[Int, Double]]()

      // Warm up + Pred 階段：讓 MiniTage 學習這些模式的同時進行預測
      for ((pc, taken) <- testSeq) {
        // 進行預測
        dut.io.in.pc.poke(pc)
        dut.io.in.update.valid.poke(true.B)
        // 傳遞結果
        val predict = dut.io.out.pred.peek().litToBoolean
        // 獲得PCPN隨遞信息
        val pcpn_tableIdx = dut.io.out.pcpn.tableIdx.peek()
        val pcpn_entryIdx = dut.io.out.pcpn.entryIdx.peek()
        val pcpn_entryTag = dut.io.out.pcpn.entryTag.peek()

        // 統計用哪張表
        tableUseCount(pcpn_tableIdx.litValue.toInt) += 1

        // 正確率統計
        if (predict == taken) {
          correct_predictions += 1
        }

        total_predictions += 1

        // 每10次预估打印一次准确率
        if (total_predictions % 10 == 0) {
          // 準確率
          val accuracy = correct_predictions.toDouble / total_predictions
          accuracyHistory :+= accuracy
          dprintln(s"Accuracy at cycle ${total_predictions}: $accuracy")
          // 表使用頻率
          val total = tableUseCount.values.sum.max(1)
          val snapshot = (0 to 4).map { i =>
            i -> tableUseCount.getOrElse(i, 0).toDouble / total
          }.toMap
          tableProportionHistory.append(snapshot)
          tableUseCount.clear()
        }

        // 每50次打印一次表使用頻率
        if (total_predictions % 50 == 0) {
          // 準確率
          val accuracy = correct_predictions.toDouble / total_predictions
          accuracyHistory :+= accuracy
          dprintln(s"Accuracy at cycle ${total_predictions}: $accuracy")
          // 表使用頻率
          val total = tableUseCount.values.sum.max(1)
          val snapshot = (0 to 4).map { i =>
            i -> tableUseCount.getOrElse(i, 0).toDouble / total
          }.toMap
          tableProportionHistory.append(snapshot)
          tableUseCount.clear()
        }

        // 打印预測信息
        dprintln(
          s"pc ${pc} taken ${taken} pred ${predict} tableIdx ${pcpn_tableIdx} entryIdx ${pcpn_entryIdx}"
        )

        // 根据结果进行修正
        dut.io.in.update.valid.poke(true.B)
        dut.io.in.update.bits.bpu.pc.poke(pc)
        dut.io.in.update.bits.bpu.actualTaken.poke(taken.B)
        dut.io.in.update.bits.bpu.isMissPredict.poke((predict != taken).B)
        dut.io.in.update.bits.pcpn.tableIdx.poke(pcpn_tableIdx)
        dut.io.in.update.bits.pcpn.entryIdx.poke(pcpn_entryIdx)
        dut.io.in.update.bits.pcpn.entryTag.poke(pcpn_entryTag)

        dut.clock.step(1)
      }

      // 打印最终的准确率
      println(
        s"Final Accuracy after $predNum predictions: $correct_predictions/$total_predictions"
      )
      println(s"Accuracy History: ${accuracyHistory.mkString(", ")}")

      // 最後的表使用統計
      println(s"[TAGE 表使用佔比統計，每50次一次]")
      tableProportionHistory.zipWithIndex.foreach { case (snapshot, i) =>
        val formatted = (0 to 4)
          .map { tid =>
            f"T$tid=${snapshot.getOrElse(tid, 0.0) * 100}%.1f%%"
          }
          .mkString(", ")
        println(f"Cycle ${i * 50}%4d - ${(i + 1) * 10 - 1}%4d: $formatted")
      }

      // 期望：在训练开始后，准确率逐渐提高
      accuracyHistory.last must be >= 0.8
    }
  }
  // "MiniTage should not explode on edge cases" in {
  //   simulate(new MiniTagePred()) { dut =>
  //     // 特殊測試：所有PC相同、或者GHR爆炸長
  //     val specialPC = 0xdeadbeef.U
  //     val testSeq = Seq.fill(100)((specialPC, true)) // 永遠跳轉

  //     dut.clock.step(5)
  //     dut.reset.poke(false.B)

  //     for ((pc, taken) <- testSeq) {
  //       dut.io.in.pc.poke(pc)
  //       dut.io.in.update.valid.poke(true.B)
  //       dut.io.in.update.bits.pc.poke(pc)
  //       dut.io.in.update.bits.actualTaken.poke(taken.B)
  //       dut.clock.step(1)
  //     }

  //     // 測試：他應該學會總是跳轉
  //     dut.io.pc.poke(specialPC)
  //     dut.io.update_valid.poke(false.B)
  //     dut.clock.step(1)
  //     dut.io.predict.expect(true.B)
  //   }
  // }

  // "MiniTage should adapt to changing patterns" in {
  //   simulate(new MiniTage()) { dut =>
  //     dut.clock.step(5)
  //     dut.reset.poke(false.B)

  //     // 先讓他學習全部跳轉
  //     for (i <- 0 until 50) {
  //       dut.io.pc.poke(i.U)
  //       dut.io.update_valid.poke(true.B)
  //       dut.io.update_pc.poke(i.U)
  //       dut.io.update_taken.poke(true.B)
  //       dut.clock.step(1)
  //     }

  //     // 突然切換：全部不跳轉
  //     var correct_predictions = 0
  //     var total_predictions = 0
  //     for (i <- 50 until 100) {
  //       dut.io.pc.poke(i.U)
  //       dut.io.update_valid.poke(true.B)
  //       dut.io.update_pc.poke(i.U)
  //       dut.io.update_taken.poke(false.B)
  //       dut.clock.step(1)

  //       dut.io.pc.poke(i.U)
  //       dut.io.update_valid.poke(false.B)
  //       dut.clock.step(1)
  //       val predict = dut.io.predict.peek().litToBoolean
  //       if (!predict) correct_predictions += 1
  //       total_predictions += 1
  //     }

  //     println(s"Recovery Accuracy: $correct_predictions/$total_predictions")
  //     correct_predictions.toDouble / total_predictions must be >= 0.5
  //   }
  // }
}
