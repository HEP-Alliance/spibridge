/*                                                                           *\
**        _____ ____  _____   _____    __                                    **
**       / ___// __ \/  _/ | / /   |  / /   HDL Lib                          **
**       \__ \/ /_/ // //  |/ / /| | / /    (c) Dolu, All rights reserved    **
**      ___/ / ____// // /|  / ___ |/ /___                                   **
**     /____/_/   /___/_/ |_/_/  |_/_____/  MIT Licence                      **
**                                                                           **
** Permission is hereby granted, free of charge, to any person obtaining a   **
** copy of this software and associated documentation files (the "Software"),**
** to deal in the Software without restriction, including without limitation **
** the rights to use, copy, modify, merge, publish, distribute, sublicense,  **
** and/or sell copies of the Software, and to permit persons to whom the     **
** Software is furnished to do so, subject to the following conditions:      **
**                                                                           **
** The above copyright notice and this permission notice shall be included   **
** in all copies or substantial portions of the Software.                    **
**                                                                           **
** THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS   **
** OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF                **
** MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.    **
** IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY      **
** CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT **
** OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR  **
** THE USE OR OTHER DEALINGS IN THE SOFTWARE.                                **
\*                                                                           */
package periph

// Copy of spinal.lib.bus.amba4.axi.Axi4SharedToApb3BridgeFixed (SpinalHDL dev,
// identical in 1.15.1) with one fix, marked FIX below: PSLVERROR is latched
// together with PRDATA in ACCESS. Upstream drives the AXI resp from
// io.apb.PSLVERROR combinationally during RESPONSE, when PSEL is already low,
// so Apb3Decoder's unmapped-address error and Apb3Router's per-slave mux have
// both moved on and every slave error comes back as OKAY.

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba3.apb._
import spinal.lib.bus.amba4.axi.{Axi4Config, Axi4Shared, Axi4ToApb3BridgePhase}


object Axi4SharedToApb3BridgeFixed{

  def getConfigs(addressWidth: Int, dataWidth: Int, idWidth: Int): (Axi4Config, Apb3Config) =
  (
    Axi4Config(
      addressWidth = addressWidth,
      dataWidth    = dataWidth,
      idWidth      = idWidth,
      useLock      = false,
      useRegion    = false,
      useCache     = false,
      useProt      = false,
      useQos       = false
    ),
    Apb3Config(
      addressWidth  = addressWidth,
      dataWidth     = dataWidth,
      selWidth      = 1,
      useSlaveError = true
    )
  )
}


case class Axi4SharedToApb3BridgeFixed(addressWidth: Int, dataWidth: Int, idWidth: Int) extends Component {

  import Axi4ToApb3BridgePhase._

  val (axiConfig,apbConfig) = Axi4SharedToApb3BridgeFixed.getConfigs(addressWidth,dataWidth,idWidth)

  val io = new Bundle{
    val axi = slave (Axi4Shared(axiConfig))
    val apb = master(Apb3(apbConfig))
  }

  val phase      = RegInit(SETUP)
  val write      = Reg(Bool())
  val readedData = Reg(Bits(dataWidth bits))
  val slaveError = Reg(Bool()) // FIX
  val id         = Reg(UInt(idWidth bits))

  io.axi.sharedCmd.ready    := False
  io.axi.writeData.ready    := False
  io.axi.writeRsp.valid     := False
  io.axi.readRsp.valid      := False

  io.apb.PSEL(0) := False
  io.apb.PENABLE := False

  switch(phase){
    is(SETUP){
      write := io.axi.sharedCmd.write
      id    := io.axi.sharedCmd.id

      when(io.axi.sharedCmd.valid && (!io.axi.sharedCmd.write || io.axi.writeData.valid)) {
        phase := ACCESS
        io.apb.PSEL(0) := True
        if (axiConfig.useStrb) {
          // Ignore write packages with no strobe signal asserted and overwrite phase + PSEL.
          when(io.axi.sharedCmd.write &&
               io.axi.writeData.strb === B(0, axiConfig.bytePerWord bits)) {
            phase := RESPONSE
            slaveError := False // FIX
            io.apb.PSEL(0) := False
            io.axi.sharedCmd.ready := True
            io.axi.writeData.ready := True
          }
        }
      }
    }
    is(ACCESS){
      io.apb.PSEL(0) := True
      io.apb.PENABLE := True

      when(io.apb.PREADY){
        readedData := io.apb.PRDATA
        slaveError := io.apb.PSLVERROR // FIX
        phase      := RESPONSE
        io.axi.sharedCmd.ready := True
        io.axi.writeData.ready := write
      }
    }
    default { // RESPONSE
      when(write) {
        io.axi.writeRsp.valid := True
        when(io.axi.writeRsp.ready) {
          phase := SETUP
        }
      }otherwise {
        io.axi.readRsp.valid := True
        when(io.axi.readRsp.ready){
          phase := SETUP
        }
      }
    }
  }

  io.apb.PADDR  := io.axi.sharedCmd.addr
  io.apb.PWDATA := io.axi.writeData.data
  io.apb.PWRITE := io.axi.sharedCmd.write

  io.axi.readRsp.resp  := slaveError ## B"0" // FIX
  io.axi.writeRsp.resp := slaveError ## B"0" // FIX
  io.axi.readRsp.id    := id
  io.axi.writeRsp.id   := id
  io.axi.readRsp.data  := readedData
  io.axi.readRsp.last  := True
}


