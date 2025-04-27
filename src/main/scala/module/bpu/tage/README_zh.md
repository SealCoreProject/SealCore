# Tage 目錄簡介

用於提供分支預測單元的常用實現.

## 設計原理

參考網頁:

## MiniTage

### 面積估計

- TageTable有4個，每個表有32條entry，每條entry：
  - pred 3 bit
  - tag 8 bit
  - u 2 bit
  - ➡ 每條13 bit
  - ➡ 每表: 32×13 = 416 bit
- 整個TAGE部分總開銷 ≈ 4×416 = 1664 bit
- base predictor (Bimodal)：
  - 假設是2bit saturating counter (根據你的代碼，應該是)
  - 32 entries × 2bit = 64bit
- GHR：
  - 最大GHR長度=32bit

➡ 合計總bit數量：
`1664 + 64 + 32 = 1760 bit ≈ 220 byte`

✅ 結論：
==對於一個小型CPU核心或者早期prototype，面積非常小，非常合理。Mini-TAGE的設計目標是「小而精」嘛，完美符合這個理念。==
