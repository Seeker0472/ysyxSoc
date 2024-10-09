package ysyx

import chisel3._
import chisel3.util._

import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.amba._
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.util._

class AXI4DelayerIO extends Bundle {
  val clock = Input(Clock())
  val reset = Input(Reset())
  val in    = Flipped(new AXI4Bundle(AXI4BundleParameters(addrBits = 32, dataBits = 32, idBits = 4)))
  val out   = new AXI4Bundle(AXI4BundleParameters(addrBits = 32, dataBits = 32, idBits = 4))
}

class axi4_delayer extends BlackBox {
  val io = IO(new AXI4DelayerIO)
}
//TODO!!!
class AXI4DelayerChisel extends Module {
  val io = IO(new AXI4DelayerIO)
  // io.in.r.elements.foreach { case (name, data) =>
  //   println(s"Name: $name, Width: ${data.getWidth}, Class: ${data.getClass}")
  // }
  /*
  ----values in each channel:
    - valid
    - ready
    - bits - AXI4BundleX
   */
  val FREQ  = (DelayerParams.FREQ).U
  val SCALE = 100.U

  //max items in queue
  val QUEUE_MAX = 16

  val rvalid = RegInit(false.B)
  val bvalid = RegInit(false.B)

  val rbuffer = Module(
    new Queue(new AXI4BundleR(AXI4BundleParameters(addrBits = 32, dataBits = 32, idBits = 4)), QUEUE_MAX)
  )
  val wbuffer = Module(
    new Queue(new AXI4BundleB(AXI4BundleParameters(addrBits = 32, dataBits = 32, idBits = 4)), QUEUE_MAX)
  )

  val rcounter = Module(new Queue(UInt(32.W), QUEUE_MAX))
  val wcounter = Module(new Queue(UInt(32.W), QUEUE_MAX))

  val rtimer = RegInit(0.U(32.W))
  val wtimer = RegInit(0.U(32.W))

  //timer
  rtimer := rtimer + 1.U
  when(io.out.ar.valid) {
    rtimer := 0.U
  }
  wtimer := wtimer + 1.U
  when(io.out.aw.valid) {
    wtimer := 0.U
  }
  //deq_logic
  rcounter.io.enq.bits  := (rtimer * FREQ) / SCALE
  wcounter.io.enq.bits  := (wtimer * FREQ) / SCALE
  rcounter.io.enq.valid := io.out.r.valid
  wcounter.io.enq.valid := io.out.b.valid

  val deq_valid_r = rcounter.io.deq.valid && io.in.r.ready && rcounter.io.deq.bits < rtimer
  val deq_valid_w = wcounter.io.deq.valid && io.in.b.ready && wcounter.io.deq.bits < wtimer
  when(rcounter.io.deq.valid && rcounter.io.deq.bits <= rtimer) {
    rvalid := true.B
  }
  when(wcounter.io.deq.valid && wcounter.io.deq.bits <= wtimer) {
    bvalid := true.B
  }

  rbuffer.io.deq.ready  := deq_valid_r
  rcounter.io.deq.ready := deq_valid_r
  wbuffer.io.deq.ready  := deq_valid_w
  wcounter.io.deq.ready := deq_valid_w

  //enq_data
  rbuffer.io.enq.bits <> io.out.r.bits
  rbuffer.io.enq.valid <> io.out.r.valid
  rbuffer.io.enq.ready <> io.out.r.ready

  wbuffer.io.enq.bits <> io.out.b.bits
  wbuffer.io.enq.valid <> io.out.b.valid
  wbuffer.io.enq.ready <> io.out.b.ready

  //output
  io.in.ar <> io.out.ar
  io.in.r.bits <> rbuffer.io.deq.bits
  io.in.r.valid := rvalid
  when(io.in.r.ready && rvalid) {
    rvalid := false.B
  }

  io.in.aw <> io.out.aw
  io.in.w <> io.out.w
  io.in.b.bits <> wbuffer.io.deq.bits
  io.in.b.valid := bvalid
  when(io.in.b.ready && bvalid) {
    bvalid := false.B
  }
}

class AXI4DelayerWrapper(implicit p: Parameters) extends LazyModule {
  val node = AXI4IdentityNode()

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    (node.in.zip(node.out)).foreach {
      case ((in, edgeIn), (out, edgeOut)) =>
        val delayer = Module(new AXI4DelayerChisel)
        delayer.io.clock := clock
        delayer.io.reset := reset
        delayer.io.in <> in
        out <> delayer.io.out
    }
  }
}

object AXI4Delayer {
  def apply()(implicit p: Parameters): AXI4Node = {
    val axi4delay = LazyModule(new AXI4DelayerWrapper)
    axi4delay.node
  }
}
