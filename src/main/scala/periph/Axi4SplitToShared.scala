package periph

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi._

// Joins AxiBridgeManager's split write/read channels into the one Axi4Shared
// bus that Axi4SharedToApb3BridgeFixed (spinal.lib's APB bridge) takes.
//
// AW goes through a one-entry register (m2sPipe). Without it the two sides
// deadlock: the APB bridge only starts a write once sharedCmd AND writeData
// are valid together, while AxiBridgeManager holds W back until AW has fired.
//
// The manager is single-outstanding, so AW and AR are never valid together;
// write still wins if they ever were. The manager uses no ids, so id is 0 out
// and dropped on the way back.
class Axi4SplitToShared(inCfg: Axi4Config, outCfg: Axi4Config) extends Component {
  val io = new Bundle {
    val axiW = slave(Axi4WriteOnly(inCfg))
    val axiR = slave(Axi4ReadOnly(inCfg))
    val shared = master(Axi4Shared(outCfg))
  }

  val aw = io.axiW.aw.m2sPipe()
  val ar = io.axiR.ar
  val cmd = io.shared.sharedCmd

  cmd.valid := aw.valid || ar.valid
  cmd.write := aw.valid
  cmd.addr := (aw.valid ? aw.addr | ar.addr).resized
  if (cmd.id != null) cmd.id := 0
  if (cmd.region != null) cmd.region := 0
  if (cmd.len != null) cmd.len := 0
  if (cmd.size != null) cmd.size := U(log2Up(outCfg.dataWidth / 8))
  if (cmd.burst != null) cmd.burst := B"01" // INCR
  if (cmd.lock != null) cmd.lock := 0
  if (cmd.cache != null) cmd.cache := 0
  if (cmd.qos != null) cmd.qos := 0
  if (cmd.user != null) cmd.user := 0
  if (cmd.prot != null) cmd.prot := 0
  aw.ready := cmd.ready
  ar.ready := cmd.ready && !aw.valid

  val wd = io.shared.writeData
  wd.valid := io.axiW.w.valid
  wd.data := io.axiW.w.data
  if (wd.strb != null) wd.strb := (if (io.axiW.w.strb != null) io.axiW.w.strb else B(wd.strb.range -> true))
  if (wd.last != null) wd.last := True
  if (wd.user != null) wd.user := 0
  io.axiW.w.ready := wd.ready

  io.axiW.b.valid := io.shared.writeRsp.valid
  if (io.axiW.b.resp != null) io.axiW.b.resp := io.shared.writeRsp.resp
  if (io.axiW.b.id != null) io.axiW.b.id := 0
  if (io.axiW.b.user != null) io.axiW.b.user := 0
  io.shared.writeRsp.ready := io.axiW.b.ready

  io.axiR.r.valid := io.shared.readRsp.valid
  io.axiR.r.data := io.shared.readRsp.data
  if (io.axiR.r.resp != null) io.axiR.r.resp := io.shared.readRsp.resp
  if (io.axiR.r.last != null) io.axiR.r.last := True
  if (io.axiR.r.id != null) io.axiR.r.id := 0
  if (io.axiR.r.user != null) io.axiR.r.user := 0
  io.shared.readRsp.ready := io.axiR.r.ready
}
