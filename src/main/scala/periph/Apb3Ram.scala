package periph

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba3.apb.{Apb3, Apb3Config}

// Zero-wait-state APB3 RAM on a synchronous-read Mem, so FPGA tools map it to
// block RAM. The read is issued in the SETUP cycle and its data is ready in
// the ACCESS cycle; writes happen in ACCESS. APB3 has no byte strobes, so
// every write is a full word.
class Apb3Ram(bytes: Int) extends Component {
  require(isPow2(bytes) && bytes >= 8, "size must be a power of two")

  val io = new Bundle {
    val apb = slave(Apb3(Apb3Ram.apbConfig(bytes)))
  }

  val words = bytes / 4
  val mem = Mem(Bits(32 bits), words)
  val idx = (io.apb.PADDR >> 2).resize(log2Up(words) bits)

  val setup = io.apb.PSEL(0) && !io.apb.PENABLE
  val access = io.apb.PSEL(0) && io.apb.PENABLE

  io.apb.PRDATA := mem.readSync(idx, enable = setup && !io.apb.PWRITE)
  mem.write(idx, io.apb.PWDATA, enable = access && io.apb.PWRITE)
  io.apb.PREADY := True
  io.apb.PSLVERROR := False
}

object Apb3Ram {
  def apbConfig(bytes: Int) = Apb3Config(addressWidth = log2Up(bytes), dataWidth = 32, useSlaveError = true)
}
