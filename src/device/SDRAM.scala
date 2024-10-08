package ysyx

import chisel3._
import chisel3.util._
import chisel3.experimental._
import chisel3.experimental.Analog

import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.amba.apb._
import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.util._

class SDRAMIO extends Bundle {
  val clk = Output(Bool())
  val cke = Output(Bool())
  val cs  = Output(Bool()) //Chip Select
  val ras = Output(Bool()) //
  val cas = Output(Bool()) //
  val we  = Output(Bool()) //
  val a   = Output(UInt(13.W)) //Address Input
  val ba  = Output(UInt(2.W)) //Bank Address Input
  val dqm = Output(UInt(4.W)) //Input/Output Mask
  val dq  = Analog(32.W) //Data Input/Outp
}

class sdram_top_axi extends BlackBox {
  val io = IO(new Bundle {
    val clock = Input(Clock())
    val reset = Input(Bool())
    val in    = Flipped(new AXI4Bundle(AXI4BundleParameters(addrBits = 32, dataBits = 32, idBits = 4)))
    val sdram = new SDRAMIO
  })
}

class sdram_top_apb extends BlackBox {
  val io = IO(new Bundle {
    val clock = Input(Clock())
    val reset = Input(Bool())
    val in    = Flipped(new APBBundle(APBBundleParameters(addrBits = 32, dataBits = 32)))
    val sdram = new SDRAMIO
  })
}

class sdram extends BlackBox {
  val io = IO(Flipped(new SDRAMIO))
}

class sdramBlock extends BlackBox with HasBlackBoxInline {
  val io = IO(new Bundle {
    val clk      = Input(Clock())
    val bank     = Input(UInt(2.W))
    val col      = Input(UInt(9.W))
    val row      = Input(UInt(13.W))
    val data_in  = Input(UInt(16.W))
    val we       = Input(Bool())
    val data_out = Output(UInt(16.W))
    val dqm      = Input(UInt(2.W))
  })
  setInline(
    "sdramBlock.v",
    """
      |module sdramBlock(
      |    input wire clk,
      |    input wire [1:0] bank,
      |    input wire [12:0] row,
      |    input wire [8:0] col,
      |    input wire [15:0] data_in,
      |    input wire we,
      |    output reg [15:0] data_out,
      |    input wire [1:0] dqm
      |);
      |    reg [15:0] regFile [3:0][8191:0][511:0];
      |
      |    always @(posedge clk) begin
      |           if(we) begin
      |            if (dqm[0] == 0) begin
      |                regFile[bank][row][col][7:0] <= data_in[7:0];
      |            end
      |            if (dqm[1] == 0) begin
      |                regFile[bank][row][col][15:8] <= data_in[15:8];
      |            end
      |          end
      |        data_out <= regFile[bank][row][col];
      |    end
      |endmodule
    """.stripMargin
  )
}

class sdramChisel extends RawModule {
  val io     = IO(Flipped(new SDRAMIO))
  val dout   = Wire(UInt(32.W))
  val out_en = Wire(Bool())
  out_en := false.B //Enable TristateOutPut
  val dq = TriStateInBuf(io.dq, dout, out_en) // io
// 4-memBlocks
  val mem1  = Module(new sdramBlock())
  val mem2  = Module(new sdramBlock())
  val mem11 = Module(new sdramBlock())
  val mem12 = Module(new sdramBlock())
//connect_clk
  mem1.io.clk  := io.clk.asClock
  mem2.io.clk  := io.clk.asClock
  mem11.io.clk := io.clk.asClock
  mem12.io.clk := io.clk.asClock

  val s_idle :: s_read :: s_write :: Nil = Enum(3)
//decode sigs
  val sig_active     = (!io.cs) && (!io.ras) && io.cas && io.we
  val sig_read       = (!io.cs) && io.ras && (!io.cas) && io.we
  val sig_write      = (!io.cs) && io.ras && (!io.cas) && (!io.we)
  val sig_write_mode = (!io.cs) && (!io.ras) && (!io.cas) && (!io.we)

  withClockAndReset(io.clk.asClock, false.B) {
    val state   = RegInit(s_idle)
    val counter = Reg(UInt(3.W))
    val data    = Reg(UInt(32.W))
    //好像DRAM控制器只会发送CAS延迟为2,burstL=2的请求
    val row          = Reg(Vec(4, UInt(13.W)))
    val col          = Reg(UInt(13.W))
    val bankid       = Reg(UInt(2.W))
    val control_code = Reg(UInt(13.W))
//bankid
    mem1.io.bank  := bankid
    mem2.io.bank  := bankid
    mem11.io.bank := bankid
    mem12.io.bank := bankid
//col
    mem1.io.col  := col(8, 0)
    mem2.io.col  := col(8, 0)
    mem11.io.col := col(8, 0)
    mem12.io.col := col(8, 0)
//row
    mem1.io.row  := row(bankid)
    mem2.io.row  := row(bankid)
    mem11.io.row := row(bankid)
    mem12.io.row := row(bankid)
//data
    data             := dq
    mem1.io.data_in  := data(31, 16)
    mem11.io.data_in := data(31, 16)
    mem2.io.data_in  := data(15, 0)
    mem12.io.data_in := data(15, 0)
//dqm
    val demdelay = Reg(UInt(4.W))
    demdelay     := io.dqm
    mem1.io.dqm  := demdelay(3, 2)
    mem11.io.dqm := demdelay(3, 2)
    mem2.io.dqm  := demdelay(1, 0)
    mem12.io.dqm := demdelay(1, 0)
//select data_out
    dout := Mux(
      col(9, 9) === 1.U,
      Cat(mem11.io.data_out, mem12.io.data_out),
      Cat(mem1.io.data_out, mem2.io.data_out)
    )
//enable output of tri_state_buf
    out_en      := state === s_read
    mem1.io.we  := Mux(col(9, 9) === 0.U, state === s_write, false.B)
    mem11.io.we := Mux(col(9, 9) === 1.U, state === s_write, false.B)
    mem2.io.we  := Mux(col(9, 9) === 0.U, state === s_write, false.B)
    mem12.io.we := Mux(col(9, 9) === 1.U, state === s_write, false.B)
    state := MuxLookup(state, s_idle)(
      List(
        s_idle -> Mux(sig_read, s_read, Mux(sig_write, s_write, s_idle)),
        s_read -> Mux(counter === 2.U, s_idle, s_read), //延迟一个周期返回
        s_write -> s_idle
      )
    )
    when(state === s_read || state === s_write) {
      counter := counter + 1.U
    }
    when(state === s_idle && sig_write_mode) {
      control_code := io.a
    }
    when(state === s_idle && sig_active) {
      row(io.ba) := io.a
      counter    := 0.U
      bankid     := io.ba
    }
    when((sig_read || sig_write)) {
      col     := io.a
      counter := 0.U
      bankid  := io.ba
    }
  }
}

class AXI4SDRAM(address: Seq[AddressSet])(implicit p: Parameters) extends LazyModule {
  val beatBytes = 4
  val node = AXI4SlaveNode(
    Seq(
      AXI4SlavePortParameters(
        Seq(
          AXI4SlaveParameters(
            address       = address,
            executable    = true,
            supportsWrite = TransferSizes(1, beatBytes),
            supportsRead  = TransferSizes(1, beatBytes),
            interleavedId = Some(0)
          )
        ),
        beatBytes = beatBytes
      )
    )
  )

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    val (in, _)      = node.in(0)
    val sdram_bundle = IO(new SDRAMIO)

    val msdram = Module(new sdram_top_axi)
    msdram.io.clock := clock
    msdram.io.reset := reset.asBool
    msdram.io.in <> in
    sdram_bundle <> msdram.io.sdram
  }
}

class APBSDRAM(address: Seq[AddressSet])(implicit p: Parameters) extends LazyModule {
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
    val (in, _)      = node.in(0)
    val sdram_bundle = IO(new SDRAMIO)

    val msdram = Module(new sdram_top_apb)
    msdram.io.clock := clock
    msdram.io.reset := reset.asBool
    msdram.io.in <> in
    sdram_bundle <> msdram.io.sdram
  }
}
