package top

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba3.apb.Apb3Decoder
import spinal.lib.bus.amba4.axi.Axi4Config
import spinal.lib.bus.misc.SizeMapping

import spibridge.{FrameDecoder, FrameEncoder, AxiBridgeManager, AxiBridgeConfig, FramerConfig, CrcParams}
import periph.{Axi4SplitToShared, Axi4SharedToApb3BridgeFixed, Apb3Ram, Apb3InfoRegs}

object SpiAxiTop {
  // Wire format is identical to cavaboris.ChipTop (20-bit address, 32-bit
  // data, CRC-32 0x104C11DB7, same tags), so python/spi_link.py and anything
  // proven here carry straight over. Only the address map behind it differs.
  val addressWidth = 20

  // Lean AXI: the bridge only ever issues single-beat, full-word, id-less
  // transfers, and every copied component already skips absent fields.
  val axi = Axi4Config(
    addressWidth = addressWidth, dataWidth = 32,
    useId = false, useRegion = false, useBurst = false, useLock = false,
    useCache = false, useSize = false, useQos = false, useLen = false,
    useLast = true, useResp = true, useProt = false, useStrb = true
  )

  val crc = CrcParams(32, BigInt("104C11DB7", 16))
  val writeTag = 0
  val readTag = 1
  val respTag = 2
  val tags = Seq(
    BigInt("A5A5A5A5", 16), // WRITE
    BigInt("5A5A5A5A", 16), // READ
    BigInt("0F0F0F0F", 16)  // RESP
  )
  val framer = FramerConfig(crc, frameBits = axi.addressWidth + axi.dataWidth + crc.degree, tags = tags)
  val bridge = AxiBridgeConfig(framer, axi, writeTag = writeTag, readTag = readTag, respTag = respTag)

  // APB map. The whole 20-bit space goes to the APB bridge, and Apb3Decoder
  // answers any address outside these windows with PSLVERROR, so a bad
  // address returns SLVERR instead of hanging the bridge.
  val ramBase = 0x00000L
  val infoBase = 0x10000L
}

// SPI -> AXI bridge from cavaboris (ChipTop's alwaysOn area), then AXI ->
// APB through Axi4SharedToApb3BridgeFixed (spinal.lib's bridge with an error
// fix), with a RAM and a small register block on APB. sclk/mosi are sampled
// on clk, so sclk must run well below clk/4. csn is unused, as in ChipTop:
// framing is CRC-delimited, not CS-delimited.
class SpiAxiTop(ramBytes: Int = 4096) extends Component {
  val io = new Bundle {
    val clk     = in Bool ()
    val reset_n = in Bool ()
    val sclk    = in Bool ()
    val mosi    = in Bool ()
    val miso    = out Bool ()
    val csn     = in Bool ()
    val leds    = out Bits (4 bits) // Apb3InfoRegs LEDS register
    val cmdSeen = out Bool ()       // one-cycle pulse per decoded command
  }

  val coreClock = ClockDomain(clock = io.clk, reset = io.reset_n,
    config = ClockDomainConfig(resetKind = ASYNC, resetActiveLevel = LOW))

  val core = new ClockingArea(coreClock) {
    // Two-stage sync on both pads so every flop agrees on whether a bit
    // shifted (see ChipTop.scala).
    val sclkSync = BufferCC(io.sclk, init = False)
    val mosiSync = BufferCC(io.mosi, init = False)
    val sclkPrev = RegNext(sclkSync) init (False)
    val sclkRise = sclkSync && !sclkPrev

    val dec = new FrameDecoder(SpiAxiTop.framer)
    dec.io.bitIn := mosiSync
    dec.io.enable := sclkRise
    io.cmdSeen := dec.io.valid

    val mgr = new AxiBridgeManager(SpiAxiTop.bridge)
    mgr.io.cmdValid := dec.io.valid
    mgr.io.cmdTag := dec.io.tagIndex
    mgr.io.cmdPayload := dec.io.payload

    val enc = new FrameEncoder(SpiAxiTop.framer)
    enc.io.start := mgr.io.respStart
    enc.io.payload := mgr.io.respPayload
    enc.io.tagIndex := mgr.io.respTag
    enc.io.enable := sclkRise
    io.miso := enc.io.bitOut

    val apbBridge = Axi4SharedToApb3BridgeFixed(addressWidth = SpiAxiTop.addressWidth, dataWidth = 32, idWidth = 1)
    val toShared = new Axi4SplitToShared(SpiAxiTop.axi, apbBridge.axiConfig)
    toShared.io.axiW <> mgr.io.axiW
    toShared.io.axiR <> mgr.io.axiR
    apbBridge.io.axi <> toShared.io.shared

    val ram = new Apb3Ram(ramBytes)
    val info = new Apb3InfoRegs
    io.leds := info.io.leds

    Apb3Decoder(master = apbBridge.io.apb, slaves = Seq(
      ram.io.apb -> SizeMapping(SpiAxiTop.ramBase, ramBytes),
      info.io.apb -> SizeMapping(SpiAxiTop.infoBase, 1 << Apb3InfoRegs.apbConfig.addressWidth)
    ))
  }
}
