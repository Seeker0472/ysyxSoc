package ysyx

import chisel3._
import chisel3.util._

import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.amba._
import freechips.rocketchip.amba.apb._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.util._

class APBDelayerIO extends Bundle {
  val clock = Input(Clock())
  val reset = Input(Reset())
  val in    = Flipped(new APBBundle(APBBundleParameters(addrBits = 32, dataBits = 32)))
  val out   = new APBBundle(APBBundleParameters(addrBits = 32, dataBits = 32))
}

class apb_delayer extends BlackBox {
  val io = IO(new APBDelayerIO)
}

class APBDelayerChisel extends Module {
  val io = IO(new APBDelayerIO)
  // io.out <> io.in
  //TODO:
  //延迟模块需要等待的周期数与设备服务请求所花费的时间有关, 并不是一个固定的常数, 因此需要在延迟模块中动态计算.
  /*
    设备运行在100MHz,NPC暂时运行在400MHz,每次时间延迟要*4
   */
  val FREQ                                              = 625.U
  val scale                                             = 100.U
  val timer                                             = RegInit(0.U(64.W))
  val count                                             = RegInit(0.U(64.W))
  val s_idle :: s_fetching :: s_delay :: s_valid :: Nil = Enum(4)
  val state                                             = RegInit(s_idle)
  state := MuxLookup(state, s_idle)(
    Seq(
      s_idle -> Mux((io.in.psel) && (io.in.penable), s_fetching, s_idle),
      s_fetching -> Mux(io.out.pready, s_delay, s_fetching),
      s_delay -> Mux(count === 1.U, s_valid, s_delay),
      s_valid -> s_idle
    )
  )
  //pass
  io.in.pwrite <> io.out.pwrite
  io.in.paddr <> io.out.paddr
  io.in.pprot <> io.out.pprot
  io.in.pwdata <> io.out.pwdata
  io.in.pstrb <> io.out.pstrb
  io.in.pauser <> io.out.pauser

  io.in.pduser <> io.out.pduser

  io.out.penable := state === s_fetching
  io.out.psel    := state === s_fetching || (state === s_idle && io.in.penable === 1.U)

  val pslverr = RegInit(false.B)
  val prdata  = RegInit(0.U(32.W))
  when(state === s_fetching && io.out.pready) { //存储结果
    pslverr := io.out.pslverr
    prdata  := io.out.prdata
  }
  io.in.pslverr := pslverr
  io.in.prdata  := prdata
  when(state === s_fetching) {
    timer := timer + 1.U
    count := (timer + 1.U) * FREQ / scale - timer - 1.U
  }
  when(state === s_delay) {
    timer := 0.U
    count := count - 1.U
  }
  io.in.pready := state === s_valid

}

class APBDelayerWrapper(implicit p: Parameters) extends LazyModule {
  val node = APBIdentityNode()

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    (node.in.zip(node.out)).foreach {
      case ((in, edgeIn), (out, edgeOut)) =>
        val delayer = Module(new APBDelayerChisel)
        // val delayer = Module(new apb_delayer)
        delayer.io.clock := clock
        delayer.io.reset := reset
        delayer.io.in <> in
        out <> delayer.io.out
    }
  }
}

object APBDelayer {
  def apply()(implicit p: Parameters): APBNode = {
    val apbdelay = LazyModule(new APBDelayerWrapper)
    apbdelay.node
  }
}
