package ysyx

import chisel3._
import chisel3.util._

import freechips.rocketchip.amba.apb._
import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.util._

class GPIOIO extends Bundle {
  val out = Output(UInt(16.W))
  val in = Input(UInt(16.W))
  val seg = Output(Vec(8, UInt(8.W)))
}

class GPIOCtrlIO extends Bundle {
  val clock = Input(Clock())
  val reset = Input(Reset())
  val in = Flipped(new APBBundle(APBBundleParameters(addrBits = 32, dataBits = 32)))
  val gpio = new GPIOIO
}

class gpio_top_apb extends BlackBox {
  val io = IO(new GPIOCtrlIO)
}

class gpioChisel extends Module {
  val io = IO(new GPIOCtrlIO)
  io.in.pready:=true.B
  io.in.pslverr:=false.B

  val led_state = RegInit(0.U(16.W))
  val segment_display = RegInit(0.U(32.W))
  val addr_led = io.in.paddr===0x10002000.U
  val addr_seg = io.in.paddr===0x10002008.U
  io.gpio.out:=led_state
  // led_state:=Cat(led_state(14,0),led_state(15,15))
  when(io.in.psel&&io.in.penable&&io.in.pwrite&&addr_led){
    led_state:=io.in.pwdata
  }
  when(io.in.psel&&io.in.penable&&io.in.pwrite&&addr_seg){
    segment_display:=io.in.pwdata
  }
def hexTo7Segment(hex: UInt): UInt = {
  MuxLookup(hex, "b1111111".U)( Seq(
    0.U -> "b0000001".U, // 0
    1.U -> "b1001111".U, // 1
    2.U -> "b0010010".U, // 2
    3.U -> "b0000110".U, // 3
    4.U -> "b1001100".U, // 4
    5.U -> "b0100100".U, // 5
    6.U -> "b0100000".U, // 6
    7.U -> "b0001111".U, // 7
    8.U -> "b0000000".U, // 8
    9.U -> "b0000100".U, // 9
    10.U -> "b0001000".U, // A
    11.U -> "b1100000".U, // B
    12.U -> "b0110001".U, // C
    13.U -> "b1000010".U, // D
    14.U -> "b0110000".U, // E
    15.U -> "b0111000".U  // F
  ))
}
def hexTo7SegmentCat(hex: UInt): UInt = {
  Cat(hexTo7Segment(hex),1.U(1.W))
}


  io.gpio.seg(0):=hexTo7SegmentCat(segment_display(3,0))
  io.gpio.seg(1):=hexTo7SegmentCat(segment_display(7,4))
  io.gpio.seg(2):=hexTo7SegmentCat(segment_display(11,8))
  io.gpio.seg(3):=hexTo7SegmentCat(segment_display(15,12))
  io.gpio.seg(4):=hexTo7SegmentCat(segment_display(19,16))
  io.gpio.seg(5):=hexTo7SegmentCat(segment_display(23,20))
  io.gpio.seg(6):=hexTo7SegmentCat(segment_display(27,24))
  io.gpio.seg(7):=hexTo7SegmentCat(segment_display(31,28))
  io.in.prdata:=io.gpio.in
  // io.gpio.out

}



class APBGPIO(address: Seq[AddressSet])(implicit p: Parameters) extends LazyModule {
  val node = APBSlaveNode(Seq(APBSlavePortParameters(
    Seq(APBSlaveParameters(
      address       = address,
      executable    = true,
      supportsRead  = true,
      supportsWrite = true)),
    beatBytes  = 4)))

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    val (in, _) = node.in(0)
    val gpio_bundle = IO(new GPIOIO)

    val mgpio = Module(new gpioChisel)
    mgpio.io.clock := clock
    mgpio.io.reset := reset
    mgpio.io.in <> in
    gpio_bundle <> mgpio.io.gpio
  }
}
