package ysyx

import chisel3._
import chisel3.util._

import freechips.rocketchip.amba.apb._
import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.util._
import java.rmi.server.UID

class PS2IO extends Bundle {
  val clk = Input(Bool())
  val data = Input(Bool())
}

class PS2CtrlIO extends Bundle {
  val clock = Input(Clock())
  val reset = Input(Bool())
  val in = Flipped(new APBBundle(APBBundleParameters(addrBits = 32, dataBits = 32)))
  val ps2 = new PS2IO
}

class ps2_top_apb extends BlackBox {
  val io = IO(new PS2CtrlIO)
}

class ps2Chisel extends Module {
  val io = IO(new PS2CtrlIO)
  //TODO:Imp
  val clk_sampling = Reg(UInt(3.W))
  clk_sampling:=Cat(clk_sampling(1,0),io.ps2.clk)
  val counter =RegInit(0.U(3.W))
  val data= Reg(UInt(16.W))
  val data_fifo=Reg(Vec(16,UInt(16.W)))//TODO:what key down/up????
  val fifo_s = RegInit(0.U(4.W))   //----
  val fifo_e = RegInit(0.U(4.W))
  val s_idle::s_receive::s_config::s_add_fifo::Nil=Enum(4)
  val state=RegInit(s_idle)
  val valid=RegInit(false.B)
  val is_break=RegInit(false.B)

  when(clk_sampling(2,2).asBool&&(~clk_sampling(1,1)).asBool){//时钟下降沿
    when(state===s_idle){
      state:=s_receive
      counter:=0.U
    }
    when(state===s_receive){
      data:=Cat(io.ps2.data,data(15,1))
      when(counter===7.U){
        // state:=Mux(io.ps2.data===1.U,s_receive,s_config)
        is_break:=io.ps2.data===1.U||is_break
        state:=s_config
        counter:=0.U
      }
      counter:=counter+1.U
    }
    when(state===s_config){
      state:=s_add_fifo
      // data:=Mux(data(7,0)===0.U,Cat(0.U(8.W),data(15,8)),data)
    }
    when(state===s_add_fifo){
      state:=s_idle
      data_fifo(fifo_e):=Mux(is_break,Mux(data(15,15)===1.U,data,Cat(data_fifo(fifo_e)(15,8),data(15,8))),Cat(0.U(8.W),data(15,8)))
      fifo_e:=Mux(data(15,15)===1.U,fifo_e,fifo_e+1.U)
      is_break:=data(15,15)===1.U
    }
  }
  io.in.pready:=true.B
  io.in.pslverr:=false.B
  io.in.prdata:=Cat(0.U(16.W),Mux(fifo_e=/=fifo_s,data_fifo(fifo_s),0.U(16.W)))
  fifo_s:=Mux(fifo_e=/=fifo_s&&(io.in.psel&&io.in.penable),fifo_s+1.U,fifo_s)
  
}

class APBKeyboard(address: Seq[AddressSet])(implicit p: Parameters) extends LazyModule {
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
    val ps2_bundle = IO(new PS2IO)

    val mps2 = Module(new ps2Chisel)
    mps2.io.clock := clock
    mps2.io.reset := reset
    mps2.io.in <> in
    ps2_bundle <> mps2.io.ps2
  }
}
