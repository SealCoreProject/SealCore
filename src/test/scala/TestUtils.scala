package testutils

/** 提供測試常用方法與配置
  */
trait HasDebugPrint {

  /* 默認開啓, 當模塊測試完後, 請在模塊內修改關閉 */
  var debugPrint = true
  var tracePrint = false
  def dprintln(x: => Any): Unit = {
    if (debugPrint) println(x)
  }
  def tprintln(x: => Any): Unit = {
    if (tracePrint) println(x)
  }
}
