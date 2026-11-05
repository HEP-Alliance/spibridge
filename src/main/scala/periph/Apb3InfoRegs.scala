package periph

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba3.apb.{Apb3, Apb3Config}

// Small register block next to the RAM, so the bring-up test exercises APB
// address decoding and the error path as well as plain memory:
//   0x0  ID       read-only, Apb3InfoRegs.id; a write is PSLVERROR
//   0x4  SCRATCH  32-bit read/write
//   0x8  LEDS     4-bit read/write, driven out on io.leds
//   else          PSLVERROR
class Apb3InfoRegs extends Component {
  val io = new Bundle {
    val apb = slave(Apb3(Apb3InfoRegs.apbConfig))
    val leds = out Bits (4 bits)
  }

  val scratch = Reg(Bits(32 bits)) init (0)
  val leds = Reg(Bits(4 bits)) init (0)
  io.leds := leds

  val access = io.apb.PSEL(0) && io.apb.PENABLE
  val offset = io.apb.PADDR(Apb3InfoRegs.apbConfig.addressWidth - 1 downto 2) @@ U"00"

  io.apb.PREADY := True
  io.apb.PRDATA := 0
  io.apb.PSLVERROR := False

  switch(offset) {
    is(0x0) {
      io.apb.PRDATA := B(Apb3InfoRegs.id, 32 bits)
      io.apb.PSLVERROR := io.apb.PWRITE
    }
    is(0x4) {
      io.apb.PRDATA := scratch
      when(access && io.apb.PWRITE) { scratch := io.apb.PWDATA }
    }
    is(0x8) {
      io.apb.PRDATA := leds.resized
      when(access && io.apb.PWRITE) { leds := io.apb.PWDATA(3 downto 0) }
    }
    default {
      io.apb.PSLVERROR := True
    }
  }
}

object Apb3InfoRegs {
  val apbConfig = Apb3Config(addressWidth = 8, dataWidth = 32, useSlaveError = true)
  val id = BigInt("5B1A9B01", 16) // must match python/ram_test.py INFO_ID
}
