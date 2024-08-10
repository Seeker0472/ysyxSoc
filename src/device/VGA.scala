package ysyx

import chisel3._
import chisel3.util._

import freechips.rocketchip.amba.apb._
import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.util._

//一个像素， R(red), G(green), B(blue), A(alpha)各占8 bit, 其中VGA不使用alpha的信息. 
class VGAIO extends Bundle {
  val r = Output(UInt(8.W))
  val g = Output(UInt(8.W))
  val b = Output(UInt(8.W))
  val hsync = Output(Bool())
  val vsync = Output(Bool())
  val valid = Output(Bool())
}

class VGACtrlIO extends Bundle {
  val clock = Input(Clock())
  val reset = Input(Bool())
  val in = Flipped(new APBBundle(APBBundleParameters(addrBits = 32, dataBits = 32)))
  val vga = new VGAIO
}

class vga_top_apb extends BlackBox {
  val io = IO(new VGACtrlIO)
}

class vgaChisel extends Module {
  val io = IO(new VGACtrlIO)
  // val frame_buf = Reg(Vec(480,Vec(640,UInt(24.W))))
  val vram=Module(new VRAM)
  vram.io.clk:=clock
  val counter = RegInit(0.U(10.W))//一行460个pix  TODO:maintain
  val v_cnt = RegInit(0.U(9.W))//一帧480行   TODO:maintain
  // val counter = RegInit(0.U(7.W))
  val s_idle::s_v_front_porch::s_v_front_pulse::s_h_front_porch::s_h_front_pulse::s_h_send::s_h_back_pulse::s_v_back_pulse::Nil = Enum(8)
  val state=RegInit(s_idle)
  state:=MuxLookup(state,s_idle)(List(
    s_idle->s_v_front_porch,
    s_v_front_porch-> Mux(counter===1.U,s_v_front_pulse,s_v_front_porch),
    s_v_front_pulse->Mux(counter===32.U,s_h_front_porch,s_v_front_pulse),
    s_h_front_porch->Mux(counter===95.U,s_h_front_pulse,s_h_front_porch),
    s_h_front_pulse->Mux(counter===47.U,s_h_send,s_h_front_pulse),
    s_h_send->Mux(counter===639.U,s_h_back_pulse,s_h_send),
    s_h_back_pulse->Mux(counter===15.U,Mux(v_cnt===479.U,s_v_back_pulse,s_h_front_porch),s_h_back_pulse),
    s_v_back_pulse->Mux(counter===9.U,s_v_front_porch,s_v_back_pulse),
  ))
  counter:=Mux((state===s_idle)||(state===s_v_front_porch&&counter===1.U)||
  (state===s_v_front_pulse&&counter===32.U)||(state===s_h_front_porch&&counter===95.U)||
  (state===s_h_front_pulse&&counter===47.U)||(state===s_h_send&&counter===639.U)||
  (state===s_h_back_pulse&&counter===15.U)||(state===s_v_back_pulse&&counter===9.U),0.U,counter+1.U)
  v_cnt:=Mux(state===s_h_front_porch&&counter===1.U,v_cnt+1.U,Mux(state===s_v_back_pulse,0x1ff.U,v_cnt))
  //output
  // io.vga.r:=frame_buf(v_cnt)(counter)(23,16)
  // io.vga.g:=frame_buf(v_cnt)(counter)(15,8)
  // io.vga.b:=frame_buf(v_cnt)(counter)(7,0)
  vram.io.col:=counter
  vram.io.row:=v_cnt
  io.vga.r:=vram.io.data_out(23,16)
  io.vga.g:=vram.io.data_out(15,8)
  io.vga.b:=vram.io.data_out(7,0)
    // io.vga.g:=0.U
  // io.vga.b:=0.U
  io.vga.hsync:=Mux(state===s_h_front_porch,false.B,true.B)
  io.vga.vsync:=Mux(state===s_v_front_porch,false.B,true.B)
  // io.vga.valid:=Mux(state=/=s_idle,true.B,false.B)
  io.vga.valid:=Mux(state===s_h_send,true.B,false.B)
  //input    
  //TODO:是不是地址计算有错误？
  val addr=io.in.paddr(23,2)
  val row=addr / 640.U
  val col=addr % 640.U
  vram.io.we:=io.in.pwrite&&io.in.psel&&io.in.penable
  vram.io.wcol:=col
  vram.io.wrow:=row
  vram.io.data_in:=io.in.pwdata(23,0)
  // when(io.in.pwrite&&io.in.psel&&io.in.penable){

  //   frame_buf(row)(col) := io.in.pwdata(23,0)
  // }
  io.in.pready:=true.B
  io.in.pslverr:=false.B
  io.in.prdata:=0.U
  
  /* 
  VS---___---------------------------------------------------------------------___----
  HS        __--XXXXXXXX-__--XXXXXXXXX-__--XXXXXXXXX-__--XXXXXXXX-__--XXXXXX-
  SM 0  1 2 3  4  5     6 3 4 5       6                                     67  1     <-State Mashine
  VA___-------------------------------------------------------------------------------
  ======state========
  s_idle           :0:
  s_v_front_porch  :1:
  s_v_front_pulse  :2:
  s_h_front_porch  :3:
  s_h_front_pulse  :4:
  s_h_send         :5:
  s_h_back_pulse   :6:
  s_v_back_pulse   :7:
  ====================
  DATA(00RRGGBB)
   */

}

class VRAM extends BlackBox with HasBlackBoxInline {
  val io = IO(new Bundle {
    val clk = Input(Clock())
    val col = Input(UInt(10.W))
    val row = Input(UInt(9.W))    
    val wcol = Input(UInt(10.W))
    val wrow = Input(UInt(9.W))
    val data_in = Input(UInt(24.W))
    val we = Input(Bool())
    val data_out = Output(UInt(24.W))
  })
  setInline("VRAM.v",
    """
    |module VRAM(
    |    input wire clk,
    |    input wire [9:0] col,
    |    input wire [8:0] row,
    |    input wire [9:0] wcol,
    |    input wire [8:0] wrow,
    |    input wire [23:0] data_in,
    |    input wire we,
    |    output reg [23:0] data_out
    |);
    |    reg [23:0] regFile [479:0][639:0];
    |
    |    always @(posedge clk) begin
    |        data_out <= regFile[row][col];
    |        if(we) begin
    |            regFile[wrow][wcol] <= data_in;
    |        end
    |    end
    |endmodule
    """.stripMargin)
}



class APBVGA(address: Seq[AddressSet])(implicit p: Parameters) extends LazyModule {
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
    val vga_bundle = IO(new VGAIO)

    val mvga = Module(new vgaChisel)
    mvga.io.clock := clock
    mvga.io.reset := reset
    mvga.io.in <> in
    vga_bundle <> mvga.io.vga
  }
}
