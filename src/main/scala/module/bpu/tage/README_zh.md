# 目錄簡介

用於提供分支預測單元的常用實現.

## Tage

### 設計簡介

TAGE(TAgged GEometric history length predictor), 也就是基于带标签的几何级数递增历史长度的分支预测算法，其核心主要是两个部份：

- 呈幾何級數遞增的分支歷史長度匹配機制
- 帶標籤的索引表項

TAGE的組成:

- 一个直接索引预测表, T0, 双峰预测方式, 用于在 Tn 表都 miss 的时候，提供基本的预测;
- 若干个基于几何级数递增的分支历史长度进行匹配的索引表项 Tn
  - 索引方式: PC 值和 GHR 的值, 继续 hash 操作
  - 标签的意义: 索引到具体的 entry 后, 需要再次进行 PC 和 GHR 的值的 hash 操作，与这个 entry 中的 tag 进行比较，才能决定是否 hit
  - 这就意味着, 索引的计算方式, 以及tag的计算方式, 是不一样的, 可以有多种方式
  - 每个表使用分支指令的地址与不同长度的全局分支历史的哈希值进行索引, 每个表 Ti 匹配的全局分支历史长度服从几何级数递增: L(i) = (int)(ai-1 * L(1))

TAGE 預測器的特點:

### 當前實現

`MiniTage.scala` 提供了一個最基本 Tage 預測器, 用於在功耗與面積極敏感的設計中使用.

- 支持:
  - 一個 base predictor（如 2-bit bimodal predictor）
  - 一個 TAGE entry table（固定 history length，簡單 tag 匹配）
- 不支持:
  - 多層次歷史
  - 複雜替換策略、壓縮器
  - U-bit aging 和 alternate prediction
