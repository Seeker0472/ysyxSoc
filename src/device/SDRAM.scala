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
  val cs  = Output(Bool())//Chip Select
  val ras = Output(Bool())//
  val cas = Output(Bool())//
  val we  = Output(Bool())//
  val a   = Output(UInt(13.W))//Address Input
  val ba  = Output(UInt(2.W))//Bank Address Input
  val dqm = Output(UInt(2.W))//Input/Output Mask
  val dq  = Analog(16.W)//Data Input/Outp
}

class sdram_top_axi extends BlackBox {
  val io = IO(new Bundle {
    val clock = Input(Clock())
    val reset = Input(Bool())
    val in = Flipped(new AXI4Bundle(AXI4BundleParameters(addrBits = 32, dataBits = 32, idBits = 4)))
    val sdram = new SDRAMIO
  })
}

class sdram_top_apb extends BlackBox {
  val io = IO(new Bundle {
    val clock = Input(Clock())
    val reset = Input(Bool())
    val in = Flipped(new APBBundle(APBBundleParameters(addrBits = 32, dataBits = 32)))
    val sdram = new SDRAMIO
  })
}

class sdram extends BlackBox {
  val io = IO(Flipped(new SDRAMIO))
}

class sdramBlock extends BlackBox with HasBlackBoxInline {
  val io = IO(new Bundle {
    val clk = Input(Clock())
    val bank = Input(UInt(2.W))
    val col = Input(UInt(13.W))
    val row = Input(UInt(9.W))
    val data_in = Input(UInt(16.W))
    val we = Input(Bool())
    val data_out = Output(UInt(16.W))
    val dqm=Input(UInt(2.W))
  })
  setInline("sdramBlock.v",
    """
    |module sdramBlock(
    |    input wire clk,
    |    input wire [1:0] bank,
    |    input wire [12:0] col,
    |    input wire [8:0] row,
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
    |                regFile[bank][col][row][7:0] <= data_in[7:0];
    |            end
    |            if (dqm[1] == 0) begin
    |                regFile[bank][col][row][15:8] <= data_in[15:8];
    |            end
    |          end
    |        data_out <= regFile[bank][col][row];
    |    end
    |endmodule
    """.stripMargin)
}
// class sdramBlock extends BlackBox with HasBlackBoxInline {
//   val io = IO(new Bundle {
//     val clk = Input(Clock())
//     val bank = Input(UInt(2.W))
//     val col = Input(UInt(13.W))
//     val row = Input(UInt(9.W))
//     val data_in = Input(UInt(16.W))
//     val we = Input(Bool())
//     val data_out = Output(UInt(16.W))
//     val dqm=Input(UInt(2.W))
//   })
//   setInline("sdramBlock.v",
//     """
//     |module sdramBlock(
//     |    input wire clk,
//     |    input wire [1:0] bank,
//     |    input wire [12:0] col,
//     |    input wire [8:0] row,
//     |    input wire [15:0] data_in,
//     |    input wire we,
//     |    output reg [15:0] data_out,
//     |    input wire [1:0] dqm
//     |);
//     |    reg [15:0] regFile [0:3][0:8191][0:511];
//     |
//     |    always @(posedge clk) begin
//     |           if(we) begin
//     |              regFile[bank][col][row] <= data_in;
//     |          end
//     |        data_out <= regFile[bank][col][row];
//     |    end
//     |endmodule
//     """.stripMargin)
// }

class sdramChisel extends RawModule {
  val io = IO(Flipped(new SDRAMIO))
  val dout = Wire(UInt(16.W))
  val out_en = Wire(Bool())
  out_en:=false.B
  val dq = TriStateInBuf(io.dq, dout, out_en) // io
  val mem = Module(new sdramBlock())
    mem.io.clk:=io.clk.asClock
  val s_idle :: s_read :: s_write :: Nil = Enum(3)
//TODO:cke as enable
  val sig_active = (!io.cs)&&(!io.ras)&&io.cas&&io.we
  val sig_read = (!io.cs)&&io.ras&&(!io.cas)&&io.we
  val sig_write = (!io.cs)&&io.ras&&(!io.cas)&&(!io.we)
  val sig_write_mode = (!io.cs)&&(!io.ras)&&(!io.cas)&&(!io.we)
  withClockAndReset(io.clk.asClock,false.B){
    val state = RegInit(s_idle)
    val counter = Reg(UInt(3.W))
    val data = Reg(UInt(16.W))
    //好像DRAM控制器只会发送CAS延迟为2,burstL=2的请求
    val row = Reg(UInt(13.W))
    val col = Reg(UInt(13.W))
    val bankid = Reg(UInt(2.W))
    val control_code = Reg(UInt(13.W))

    mem.io.bank:=bankid
    mem.io.row:=col(8,0)
    mem.io.col:=row
    // mem.io.data_in:=dq
    data:=dq
    mem.io.data_in:=data
    val demdelay=Reg(UInt(2.W))
    demdelay:=io.dqm
    mem.io.dqm:=demdelay
    dout:=mem.io.data_out
    out_en:=state===s_read
    // mem.io.we:=state===s_write&&((counter===0.U&&dq(0,0)===0.U)||(counter===1.U&&dq(1,1)===0.U))
    mem.io.we:=state===s_write&&(counter===0.U||counter===1.U)
    state := MuxLookup(state,s_idle)(List(
      s_idle -> Mux(sig_read,s_read,Mux(sig_write,s_write,s_idle)),
      s_read -> Mux(counter===4.U,s_idle,s_read),
      s_write -> Mux(counter===2    .U,s_idle,s_write),
    ))
    when(state===s_idle&&sig_write_mode){
      control_code :=io.a
    }
    when(state===s_idle&&sig_active){
      row:=io.a
      counter:=0.U
    }
    when(state===s_idle&&(sig_read||sig_write)){
      col:=io.a
      counter:=0.U
      bankid:=io.ba
    }
    when(state===s_read||state===s_write){
      counter := counter+1.U
    }
    when((state===s_read&&counter===0.U)||(state===s_write)){
      col:=col+1.U
    }
  }
}


class AXI4SDRAM(address: Seq[AddressSet])(implicit p: Parameters) extends LazyModule {
  val beatBytes = 4
  val node = AXI4SlaveNode(Seq(AXI4SlavePortParameters(
    Seq(AXI4SlaveParameters(
        address       = address,
        executable    = true,
        supportsWrite = TransferSizes(1, beatBytes),
        supportsRead  = TransferSizes(1, beatBytes),
        interleavedId = Some(0))
    ),
    beatBytes  = beatBytes)))

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    val (in, _) = node.in(0)
    val sdram_bundle = IO(new SDRAMIO)

    val msdram = Module(new sdram_top_axi)
    msdram.io.clock := clock
    msdram.io.reset := reset.asBool
    msdram.io.in <> in
    sdram_bundle <> msdram.io.sdram
  }
}

class APBSDRAM(address: Seq[AddressSet])(implicit p: Parameters) extends LazyModule {
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
    val sdram_bundle = IO(new SDRAMIO)

    val msdram = Module(new sdram_top_apb)
    msdram.io.clock := clock
    msdram.io.reset := reset.asBool
    msdram.io.in <> in
    sdram_bundle <> msdram.io.sdram
  }
}
