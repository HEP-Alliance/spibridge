package top

import spinal.core._
import spinal.lib._

// Arty Z7-20 (PL only) wrapper around SpiAxiTop. Port names match
// arty_z7_20.xdc.
//   clk      125 MHz board oscillator (H16)
//   btn0     held = reset; a power-on reset also runs after configuration
//   spi_*    Pmod JA, driven by the FT232H
//   led      LD0..LD3 = Apb3InfoRegs LEDS register
//   led4_b   heartbeat, so a blinking LD4 means the bitstream is running
//   led5_g   lit ~0.1 s after every decoded SPI command
// The RGB LEDs are dimmed to 1/8 duty; at full drive they are glaring.
class ArtyTop extends Component {
  val io = new Bundle {
    val clk = in Bool ()
    val btn0 = in Bool ()
    val spi_sclk = in Bool ()
    val spi_mosi = in Bool ()
    val spi_miso = out Bool ()
    val spi_csn = in Bool ()
    val led = out Bits (4 bits)
    val led4_b = out Bool ()
    val led5_g = out Bool ()
  }
  noIoPrefix()

  // BOOT reset: registers start from their init values when the bitstream
  // loads, which is all the power-on reset needs.
  val boot = new ClockingArea(ClockDomain(io.clk, config = ClockDomainConfig(resetKind = BOOT))) {
    val btn = BufferCC(io.btn0, init = False)
    val porCount = Reg(UInt(20 bits)) init (0)
    val porDone = porCount.andR
    when(!porDone) { porCount := porCount + 1 }
    when(btn) { porCount := 0 }
    val resetN = RegNext(porDone) init (False)

    val heart = Reg(UInt(27 bits)) init (0)
    heart := heart + 1
    val dim = heart(2 downto 0) === 0

    val cmdStretch = Reg(UInt(24 bits)) init (0)
    when(cmdStretch =/= 0) { cmdStretch := cmdStretch - 1 }
  }

  val top = new SpiAxiTop(ramBytes = 4096)
  top.io.clk := io.clk
  top.io.reset_n := boot.resetN
  top.io.sclk := io.spi_sclk
  top.io.mosi := io.spi_mosi
  top.io.csn := io.spi_csn
  io.spi_miso := top.io.miso
  io.led := top.io.leds

  // cmdSeen comes from the core clock domain; same clock, so no CDC.
  boot.cmdStretch.addTag(crossClockDomain)
  when(top.io.cmdSeen) { boot.cmdStretch := (1 << 24) - 1 }

  io.led4_b := boot.heart.msb && boot.dim
  io.led5_g := boot.cmdStretch =/= 0 && boot.dim
}

object ArtyTopVerilog {
  def main(args: Array[String]): Unit = {
    val outDir = args.headOption.getOrElse("build")
    SpinalConfig(mode = Verilog, targetDirectory = outDir).generate(new ArtyTop)
  }
}
