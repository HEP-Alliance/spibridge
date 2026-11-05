package spibridge

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi.{Axi4Ax, Axi4Config, Axi4WriteOnly, Axi4ReadOnly}

// Single-outstanding: one decoded command in, one AXI transaction, one
// response frame out, then idle again. No bursts, no pipelining — the frame
// protocol above this is single-outstanding too, so there is nothing to gain
// from more complexity here.
//
// Frame payload layout (both directions): addr in the upper half, data in
// the lower half. A read command's lower half is unused. A response's upper
// half carries the AXI resp code (B or R) in its low 2 bits, zero above;
// 0 = OKAY, 2 = SLVERR. Which one a frame *is* comes entirely from its
// tag, decided upstream by FrameDecoder/FrameEncoder — this class never
// looks at CRC residuals, only at the tag index it's handed.
case class AxiBridgeConfig(framer: FramerConfig, axi: Axi4Config,
                            writeTag: Int, readTag: Int, respTag: Int) {
  require(framer.dataBits == axi.addressWidth + axi.dataWidth,
    "frame payload must be exactly addr ++ data wide")
}

object BridgeState extends SpinalEnum {
  val IDLE, AW, W, BRESP, AR, R, RESPOND = newElement()
}

class AxiBridgeManager(cfg: AxiBridgeConfig) extends Component {
  val io = new Bundle {
    val cmdValid = in Bool ()
    val cmdTag = in UInt (cfg.framer.tagBits bits)
    val cmdPayload = in Bits (cfg.framer.dataBits bits)

    val respStart = out Bool ()
    val respTag = out UInt (cfg.framer.tagBits bits)
    val respPayload = out Bits (cfg.framer.dataBits bits)

    val axiW = master(Axi4WriteOnly(cfg.axi))
    val axiR = master(Axi4ReadOnly(cfg.axi))
  }

  // Fixed, non-negotiable defaults for every optional Ax field this bridge
  // never varies: single beat, incrementing burst, no id/lock/cache/qos/user.
  def fixAx(ax: Axi4Ax): Unit = {
    if (ax.id != null) ax.id := 0
    if (ax.region != null) ax.region := 0
    if (ax.len != null) ax.len := 0
    if (ax.size != null) ax.size := U(log2Up(cfg.axi.dataWidth / 8))
    if (ax.burst != null) ax.burst := B"01" // INCR
    if (ax.lock != null) ax.lock := 0
    if (ax.cache != null) ax.cache := 0
    if (ax.qos != null) ax.qos := 0
    if (ax.user != null) ax.user := 0
    if (ax.prot != null) ax.prot := 0
  }

  io.axiW.aw.valid := False
  io.axiW.aw.addr := 0
  fixAx(io.axiW.aw.payload)
  io.axiW.w.valid := False
  io.axiW.w.data := 0
  if (io.axiW.w.strb != null) io.axiW.w.strb := 0
  if (io.axiW.w.last != null) io.axiW.w.last := False
  if (io.axiW.w.user != null) io.axiW.w.user := 0
  io.axiW.b.ready := False
  io.axiR.ar.valid := False
  io.axiR.ar.addr := 0
  fixAx(io.axiR.ar.payload)
  io.axiR.r.ready := False

  import BridgeState._
  val state = Reg(BridgeState()) init (IDLE)
  val addr = Reg(UInt(cfg.axi.addressWidth bits))
  val wdata = Reg(Bits(cfg.axi.dataWidth bits))
  val rdata = Reg(Bits(cfg.axi.dataWidth bits))
  val resp = Reg(Bits(2 bits)) init (0)

  io.respStart := False
  io.respTag := U(cfg.respTag, cfg.framer.tagBits bits)
  io.respPayload := resp.resize(cfg.axi.addressWidth bits) ## rdata

  switch(state) {
    is(IDLE) {
      when(io.cmdValid) {
        addr := io.cmdPayload(cfg.framer.dataBits - 1 downto cfg.axi.dataWidth).asUInt.resized
        wdata := io.cmdPayload(cfg.axi.dataWidth - 1 downto 0)
        state := (io.cmdTag === U(cfg.writeTag, cfg.framer.tagBits bits)) ? AW | AR
      }
    }
    is(AW) {
      io.axiW.aw.valid := True
      io.axiW.aw.addr := addr
      when(io.axiW.aw.fire) { state := W }
    }
    is(W) {
      io.axiW.w.valid := True
      io.axiW.w.data := wdata
      if (io.axiW.w.strb != null) io.axiW.w.strb.setAll()
      if (io.axiW.w.last != null) io.axiW.w.last := True
      when(io.axiW.w.fire) { state := BRESP }
    }
    is(BRESP) {
      io.axiW.b.ready := True
      when(io.axiW.b.fire) {
        rdata := 0
        resp := (if (io.axiW.b.resp != null) io.axiW.b.resp else B"00")
        state := RESPOND
      }
    }
    is(AR) {
      io.axiR.ar.valid := True
      io.axiR.ar.addr := addr
      when(io.axiR.ar.fire) { state := R }
    }
    is(R) {
      io.axiR.r.ready := True
      when(io.axiR.r.fire) {
        rdata := io.axiR.r.data
        resp := (if (io.axiR.r.resp != null) io.axiR.r.resp else B"00")
        state := RESPOND
      }
    }
    is(RESPOND) {
      io.respStart := True
      state := IDLE
    }
  }
}
