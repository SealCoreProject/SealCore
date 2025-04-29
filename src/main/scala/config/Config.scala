package config

/** 用於選擇架構寬度。
  *
  * 這個機器字長雖然是架構無關內容，理應在Config中指定。
  *
  * 但是考慮到目前各個架構基本上都只支持一個，所以將這個選項的指定下放到各個ISAConfig中。
  */
object XLen extends Enumeration {
  val _32, _64 = Value
}

/** 用於指定Instr的長度, 目前不支持壓縮指令
  */
object InstrLen extends Enumeration {
  val _32 = Value
}

/** 這個是一個全局的配置表，用於控制項目功能啓動、代碼的選擇編譯等等。
  *
  * 在這個配置表中，不應該出現任何 RTL 代碼，僅僅應該用於指定各種封裝好的選項
  *
  * 爲確保封裝可控，我們強制要求任何出現在 `Config` 中的選項都應該是 `Enum`
  */
private[config] object GlobalConfig {
  def apply() = Map(
    "XLen" -> XLen._32,
    "InstrLen" -> InstrLen._32
  )
}

/** 這個是用於引入基礎配置的
  */
object Config {
  var config: Map[String, Any] = GlobalConfig()
  def get(field: String) = {
    config(field).asInstanceOf[Boolean]
  }
  def getLong(field: String) = {
    config(field).asInstanceOf[Long]
  }
  def getInt(field: String) = {
    config(field).asInstanceOf[Int]
  }
  def getString(field: String) = {
    config(field).asInstanceOf[String]
  }
  def getT[T](field: String): T = {
    config(field).asInstanceOf[T]
  }
}
