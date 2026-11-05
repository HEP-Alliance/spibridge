package spibridge

import spinal.core._
import spinal.lib.OHToUInt

// Same bit-serial CRC math as src/main/python/crc.py, kept as the golden
// model. Framing is protocol-agnostic: any bit source (SPI MOSI, UART RX, ...)
// can drive FrameDecoder one bit per active clock edge, and any bit sink can
// be driven by FrameEncoder the same way. Neither component owns a clock
// domain of its own — the instantiator decides what clocks them (e.g. SCLK
// directly, no SPI peripheral in between) and how AXI gets driven from the
// decoded frames; that routing decision lives outside this file on purpose.

case class CrcParams(degree: Int, poly: BigInt) {
  require(((poly >> degree) & 1) == 1, "poly must include the degree bit")
  val polyLow: BigInt = poly & ((BigInt(1) << degree) - 1)

  // x^e mod poly, computed once at elaboration time (mirrors crc.py's x_pow).
  def xPow(e: Int): BigInt = {
    var v = BigInt(1) << e
    while (v.bitLength > degree) {
      v = v ^ (poly << (v.bitLength - degree - 1))
    }
    v
  }
}

// frameBits is the total wire length of every frame (payload + CRC), fixed
// across all commands. Different commands are distinguished purely by which
// `tags` value the frame's CRC residual targets (see crc.py's safe_targets).
case class FramerConfig(crc: CrcParams, frameBits: Int, tags: Seq[BigInt]) {
  require(frameBits > crc.degree, "frameBits must be larger than the CRC degree")
  require(tags.nonEmpty, "at least one tag is required")
  val dataBits: Int = frameBits - crc.degree
  val tagBits: Int = log2Up(tags.length)
}

private object CrcLogic {
  // One LFSR step: shift left, XOR in the poly's low taps if the vacated top
  // bit was set. This is the entire feedback network, mapped straight from
  // crc.py's _advance.
  def advance(reg: Bits, crc: CrcParams): Bits = {
    val carry = reg.msb
    val shifted = (reg(crc.degree - 2 downto 0) ## False).asBits
    val polyLow = B(crc.polyLow, crc.degree bits)
    Mux(carry, shifted ^ polyLow, shifted)
  }
}

// Streams bits in, flags the cycle a valid frame just completed, and hands
// out its payload + which tag matched. Cost: one `frameBits`-wide shift
// register (to recover the payload) and one `degree`-wide LFSR register (the
// running CRC of the last frameBits bits) plus a handful of tag comparators
// — no per-tag replication of either register.
class FrameDecoder(cfg: FramerConfig) extends Component {
  val io = new Bundle {
    val bitIn = in Bool ()
    // High for exactly the cycles a new bit is actually present on bitIn.
    // Tie True when bitIn already changes exactly once per active clock
    // edge (as every existing testbench does); pulse it once per detected
    // SCLK edge when the clock this component runs on is faster than the
    // real bit rate (e.g. shares a clock domain with the rest of the SoC
    // instead of being clocked directly by SCLK).
    val enable = in Bool ()
    val valid = out Bool ()
    val tagIndex = out UInt (cfg.tagBits bits)
    val payload = out Bits (cfg.dataBits bits)
  }

  val degree = cfg.crc.degree
  val n = cfg.frameBits
  val shiftConst = B(cfg.crc.xPow(n), degree bits)

  val win = Reg(Bits(n bits)) init (0)
  val reg = Reg(Bits(degree bits)) init (0)
  val loaded = Reg(UInt(log2Up(n + 1) bits)) init (0)
  val skip = Reg(UInt(log2Up(n) bits)) init (0)

  // Bit about to leave the window, sampled before this cycle's shift.
  val leavingBit = win.msb
  val nextWin = (win(n - 2 downto 0) ## io.bitIn).asBits

  val advanced = CrcLogic.advance(reg, cfg.crc)
  val withLeaving = Mux(leavingBit, advanced ^ shiftConst, advanced)
  val nextReg = withLeaving ^ (B(0, degree - 1 bits) ## io.bitIn.asBits)

  val loadedNext = Mux(loaded === n, loaded, loaded + 1)
  val full = loadedNext === n

  val tagHits = cfg.tags.map(t => nextReg === B(t, degree bits))
  val anyHit = tagHits.reduce(_ || _)
  val matchNow = io.enable && full && (skip === 0) && anyHit

  io.valid := matchNow
  io.tagIndex := OHToUInt(Vec(tagHits).asBits)
  io.payload := nextWin(n - 1 downto degree)

  when(io.enable) {
    win := nextWin
    reg := nextReg
    loaded := loadedNext
    when(matchNow) {
      skip := U(n - 1, log2Up(n) bits)
    } elsewhen (skip =/= 0) {
      skip := skip - 1
    }
  }
}

// Loads a payload + chosen tag on `start`, then shifts the framed bits (data
// MSB-first, followed by the CRC that targets that tag) out of `bitOut` one
// per active clock edge over the next `frameBits` cycles. The CRC is
// accumulated on the fly, in the same pass as the data bits go out, using
// the direct_crc form from crc.py: one LFSR step plus one conditional XOR
// per bit, no separate flush pass needed.
class FrameEncoder(cfg: FramerConfig) extends Component {
  val io = new Bundle {
    val start = in Bool () // loads payload+tag; not gated by `enable`
    // High for exactly the cycles bitOut should advance to the next bit.
    // See FrameDecoder.io.enable for when to pulse vs. tie True.
    val enable = in Bool ()
    val payload = in Bits (cfg.dataBits bits)
    val tagIndex = in UInt (cfg.tagBits bits)
    val bitOut = out Bool ()
    val busy = out Bool ()
    val done = out Bool ()
  }

  val degree = cfg.crc.degree
  val dataBits = cfg.dataBits
  val n = cfg.frameBits
  val cConst = B(cfg.crc.xPow(degree), degree bits)
  val targets = Vec(cfg.tags.map(t => B(t, degree bits)))

  val outShift = Reg(Bits(dataBits bits)) init (0)
  val acc = Reg(Bits(degree bits)) init (0)
  val crcShift = Reg(Bits(degree bits)) init (0)
  val counter = Reg(UInt(log2Up(n + 1) bits)) init (0)
  val busy = Reg(Bool()) init (False)

  val inData = counter < dataBits
  val currentBit = Mux(inData, outShift.msb, crcShift.msb)
  io.bitOut := currentBit
  io.busy := busy
  io.done := busy && (counter === n - 1)

  val advanced = CrcLogic.advance(acc, cfg.crc)
  val nextAcc = Mux(outShift.msb, advanced ^ cConst, advanced)

  when(io.start) {
    outShift := io.payload
    acc := 0
    counter := 0
    busy := True
  } elsewhen (busy && io.enable) {
    when(inData) {
      outShift := (outShift(dataBits - 2 downto 0) ## False).asBits
      acc := nextAcc
      when(counter === dataBits - 1) {
        crcShift := nextAcc ^ targets(io.tagIndex)
      }
    } otherwise {
      crcShift := (crcShift(degree - 2 downto 0) ## False).asBits
    }
    counter := counter + 1
    when(counter === n - 1) {
      busy := False
    }
  }
}
