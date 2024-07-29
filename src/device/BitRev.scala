package ysyx

import chisel3._
import chisel3.util._

class bitrev extends BlackBox {
  val io = IO(Flipped(new SPIIO(1)))
}

class bitrevChisel extends RawModule { // we do not need clock and reset
  val io = IO(Flipped(new SPIIO(1)))

  withClock(io.sck.asClock) {
    val data = Reg(UInt(8.W))
    val cnt  = Reg(UInt(3.W))
    val out   = Reg(Bool())
    io.miso := 0.U
    when((!io.ss).asBool) {
      when(!out) {
        data := Cat(data(6, 0), io.mosi)
      }.otherwise {
        io.miso := data(0, 0)
        data    := Cat(0.U(1.W), data(7, 1))
      }
      when(cnt === 7.U) {
        out := ~out
      }
      cnt := cnt + 1.U
    }.otherwise {
      data := 0.U
      cnt  := 0.U
      out   := false.B
    }
  }
}
