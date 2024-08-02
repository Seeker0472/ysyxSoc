package ysyx

import chisel3._
import chisel3.util._
import chisel3.experimental.Analog

import freechips.rocketchip.amba.apb._
import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.util._

class QSPIIO extends Bundle {
  val sck  = Output(Bool())
  val ce_n = Output(Bool()) //含义与SPI总线协议中的SS相同, 低电平有效
  val dio  = Analog(4.W)
}

class psram_top_apb extends BlackBox {
  val io = IO(new Bundle {
    val clock = Input(Clock())
    val reset = Input(Reset())
    val in    = Flipped(new APBBundle(APBBundleParameters(addrBits = 32, dataBits = 32)))
    val qspi  = new QSPIIO
  })
}

class psram extends BlackBox {
  val io = IO(Flipped(new QSPIIO))
}
//只需要实现SPI Mode的Qual IO Read和Quad IO Write两种命令
//QIR--EBh
//QIW--38h
class psramChisel extends RawModule {
  val io     = IO(Flipped(new QSPIIO))
  val out_en = Wire(Bool())
  val dout   = Wire(UInt(4.W))
  out_en := false.B
  dout   := 0.U
  val di = TriStateInBuf(io.dio, dout, out_en) // change this if you need
  // val is_QIR = io.dio === BitPat("b1011")
  // val is_QIW = io.dio === BitPat("b1000")
  //  val asyncReset = AsyncReset(io.ce_n)
  val qpi_mode = withClockAndReset(io.sck.asClock, io.ce_n)(RegInit(false.B))

  withClockAndReset(io.sck.asClock, io.ce_n.asAsyncReset) {
    val s_idle :: s_w_addr :: s_set_qpi :: s_wr_data :: s_wait_read :: s_ra_data :: Nil = Enum(6)

    val control_code = RegInit(0.U(8.W))
    // val data_count   = RegInit(0.U(3.W))
    val data_count  = RegInit(0.U(3.W))
    val state       = RegInit(s_idle)
    val data_buffer = RegInit(0.U(32.W))
    // val data_read  = Reg(32.W)
    val addr     = RegInit(0.U(32.W))
    val write_en = RegInit(false.B)

    val psram_w = Module(new psram_write())
    val psram_r = Module(new psram_read())

    psram_r.io.addr := Cat(0x80.U(8.W), addr(23, 0))
    psram_w.io.addr := Cat(0x80.U(8.W), addr(23, 0)) + data_count(2, 1)

    psram_r.io.enable := state === s_wait_read && data_count === 3.U
    // psram_r.io.enable := state === s_wait_read && data_count === 3.U
    psram_w.io.enable := state === s_wr_data && data_count(0, 0) === 1.U

    psram_r.io.clock := io.sck.asClock
    psram_w.io.clock := io.sck.asClock
    // psram_w.io.enable := false.B

    // data_read:=psram_r.io.data
    // psram_w.io.data := Cat(data_buffer(3,0),di,data_buffer(11,4),data_buffer(19,12),data_buffer(27,20))
    psram_w.io.data := Cat(0.U(24.W), data_buffer(3, 0), di)

    // when((!io.ce_n).asBool) {
    //如果是s_wait_read/s_w_addr就应该mod
    data_count := Mux(
      qpi_mode && state === s_idle,
      Mux(data_count === 1.U, 0.U, 1.U),
      Mux(
        state === s_w_addr || state === s_wait_read,
        Mux(
          state === s_wait_read,
          Mux(data_count === 6.U, 0.U, data_count + 1.U),
          Mux(data_count === 5.U, 0.U, data_count + 1.U)
        ),
        data_count + 1.U
      )
    )
    //状态机转换
    //TODO:手册上的tACLK是什么
    state := MuxLookup(state, s_idle)(
      List(
        s_idle -> Mux((data_count === 7.U && (~qpi_mode)) || (data_count === 1.U && qpi_mode), Mux(Cat(control_code(6, 0), di(0, 0)) === 0x35.U, s_set_qpi, s_w_addr), s_idle),
        s_w_addr -> Mux(
          data_count === 5.U,
          Mux(control_code === 0xeb.U, s_wait_read, s_wr_data),
          s_w_addr
        ),
        s_set_qpi -> s_set_qpi,
        s_wait_read -> Mux(data_count === 6.U, s_ra_data, s_wait_read),
        s_ra_data -> Mux(data_count =/= 7.U, s_ra_data, s_idle),
        s_wr_data -> Mux(data_count =/= 7.U, s_wr_data, s_idle)
      )
    )
    out_en := state === s_ra_data
    when(state === s_idle) { //接收控制信号
      control_code := Mux(qpi_mode, Cat(control_code(3, 0), di(3, 0)), Cat(control_code(6, 0), di(0, 0)))
    }
    when(state === s_w_addr) { //接收地址
      addr := Cat(addr(27, 0), di)
    }
    when(state === s_wait_read && data_count === 3.U) { //延迟6个周期,选一个周期调用DIPC
      // psram_r.io.enable := true.B
      val data = psram_r.io.data
      data_buffer := Cat(data(7, 0), data(15, 8), data(23, 16), data(31, 24))
    }
    when(state === s_wr_data) { //写入数据,选一个周期调用DIPC
      data_buffer := Cat(data_buffer(27, 0), di)
    }
    when(state === s_ra_data) { //读取数据
      //TODO:在这里更新
      data_buffer := Cat(data_buffer(27, 0), 0.U(4.W))
    }
    when(state === s_set_qpi) {
      qpi_mode := true.B
    }
    dout := data_buffer(31, 28)
  }

}
class psram_read extends BlackBox with HasBlackBoxInline {
  val io = IO(new Bundle {
    val addr   = Input(UInt(32.W))
    val enable = Input(Bool())
    val data   = Output(UInt(32.W))
    val clock  = Input(Clock())
  })
  setInline(
    "psram_read.v",
    """import "DPI-C" function void psram_read(input int  addr, output int data);
      |module psram_read(
      |  input [31:0] addr,
      |  output reg [31:0] data,
      |  input enable,
      |  input clock
      |);
      |always @(negedge clock) begin
      |   if (enable) 
      |      psram_read(addr,data);
      |   else
      |      data=0;
      | end
      |endmodule
    """.stripMargin
  )
}
class psram_write extends BlackBox with HasBlackBoxInline {
  val io = IO(new Bundle {
    val addr   = Input(UInt(32.W))
    val enable = Input(Bool())
    val data   = Input(UInt(32.W))
    val clock  = Input(Clock())
  })
  setInline(
    "psram_write.v",
    """import "DPI-C" function void psram_write(input int  addr, input int data);
      |module psram_write(
      |  input [31:0] addr,
      |  input [31:0] data,
      |  input enable,
      |  input clock
      |);
      |always @(negedge clock) begin
      |   if (enable) 
      |      psram_write(addr,data);
      | end
      |endmodule
    """.stripMargin
  )
}

class APBPSRAM(address: Seq[AddressSet])(implicit p: Parameters) extends LazyModule {
  val node = APBSlaveNode(
    Seq(
      APBSlavePortParameters(
        Seq(APBSlaveParameters(address = address, executable = true, supportsRead = true, supportsWrite = true)),
        beatBytes = 4
      )
    )
  )

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    val (in, _)     = node.in(0)
    val qspi_bundle = IO(new QSPIIO)

    val mpsram = Module(new psram_top_apb) //TODO:可以在这里添加进入QPI模式的控制协议？
    mpsram.io.clock := clock
    mpsram.io.reset := reset
    mpsram.io.in <> in
    qspi_bundle <> mpsram.io.qspi
  }
}
