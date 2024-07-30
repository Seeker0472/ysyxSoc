package ysyx

import chisel3._
import chisel3.util._

import freechips.rocketchip.amba.apb._
import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.util._

class SPIIO(val ssWidth: Int = 8) extends Bundle {
  val sck = Output(Bool())
  val ss = Output(UInt(ssWidth.W))
  val mosi = Output(Bool())
  val miso = Input(Bool())
}
//^^^-IOs
class spi_top_apb extends BlackBox {
  val io = IO(new Bundle {
    val clock = Input(Clock())
    val reset = Input(Reset())
    val in =
      Flipped(new APBBundle(APBBundleParameters(addrBits = 32, dataBits = 32)))
    val spi = new SPIIO
    val spi_irq_out = Output(Bool())
  })
}

class flash extends BlackBox {
  val io = IO(Flipped(new SPIIO(1)))
}

class APBSPI(address: Seq[AddressSet])(implicit p: Parameters)
    extends LazyModule {
  val node = APBSlaveNode(
    Seq(
      APBSlavePortParameters(
        Seq(
          APBSlaveParameters(
            address = address,
            executable = true,
            supportsRead = true,
            supportsWrite = true
          )
        ),
        beatBytes = 4
      )
    )
  )

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    val (in, _) = node.in(0)
    val spi_bundle = IO(new SPIIO)

    val mspi = Module(new spi_top_apb) // spi_top

    val s_idle :: s_common :: s_w_ss :: s_w_addr :: s_w_con :: s_wait_ready :: s_valid :: s_reset :: s_read_data :: Nil =
      Enum(9)
    val state = RegInit(s_idle)
    //是否进入xip模式
    val is_xip = in.paddr === BitPat("b0011????????????????????????????")
    //TODO:正常模式读取flash会出错
    val is_cancel = in.paddr ===BitPat("b00010000000000000001000000011000")&&in.pwdata(0,0)===0.U(1.W)&&in.pwrite
    //状态机
    state := MuxLookup(state, s_common)(
      List(
        s_idle ->Mux(in.psel,Mux(is_xip,s_w_ss,s_common),s_idle),
        s_common -> Mux(is_cancel, s_idle, s_common),
        s_w_ss -> Mux(mspi.io.in.pready, s_w_addr, s_w_ss),
        s_w_addr -> Mux(mspi.io.in.pready, s_w_con, s_w_addr),
        s_w_con -> Mux(mspi.io.in.pready, s_wait_ready, s_w_con),
        // s_wait_ready -> Mux(mspi.io.in.prdata(8,8)===0.U&&mspi.io.in.pready, s_read_data, s_wait_ready),//应该是查询中断/控制寄存器中
        s_wait_ready -> Mux(mspi.io.spi_irq_out, s_read_data, s_wait_ready),//应该是查询中断/控制寄存器中
        s_read_data -> Mux(mspi.io.in.pready, s_reset, s_read_data),
        s_reset -> Mux(mspi.io.in.pready, s_valid, s_reset),
        s_valid -> Mux(in.pready, s_idle, s_valid)//in的ready信号(axi向spi发送的信号？)
        // s_valid -> Mux(true.B, s_valid, s_idle)//先不管，只停留一个周期
      )
    )
    //生成addr(spi的寄存器地址)
    val xip_addr = MuxLookup(state, 0x10001000.U(32.W))(
      List(
        s_w_ss -> 0x10001018.U(32.W),
        s_w_addr -> 0x10001004.U(32.W),
        s_w_con -> 0x10001010.U(32.W),
        s_wait_ready -> 0x10001010.U(32.W),
        s_reset -> 0x10001018.U(32.W),
        s_valid -> 0x10001000.U(32.W),
        s_read_data -> 0x10001000.U(32.W),
      )
    )
    //生成data(向spi的寄存器写数据(地址已经在上面指定))
    val xip_w_data = MuxLookup(state,0.U(32.W))(
      List(
        s_w_ss -> 1.U(32.W),
        s_w_addr -> Cat(0x03.U(8.W),in.paddr(23,0)),//03是指读操作
        s_w_con -> 0x1540.U(32.W),
        s_reset -> 0.U(32.W)
      )
    )
    //当前是否在xip模式
    val xip_mode = state =/= s_idle && state=/=s_common


    mspi.io.clock := clock
    mspi.io.reset := reset

    mspi.io.in.psel := Mux(xip_mode,true.B,Mux(state===s_common,in.psel,false.B))
    mspi.io.in.penable := Mux(xip_mode,true.B,Mux(state===s_common,in.penable,false.B))
    mspi.io.in.pwrite := Mux(xip_mode,state===s_w_addr||state===s_w_con||state===s_w_ss||state===s_reset,in.pwrite)//WTF

    mspi.io.in.paddr := Mux(xip_mode,xip_addr,in.paddr)
    mspi.io.in.pprot := Mux(xip_mode,1.U,in.pprot)
    mspi.io.in.pwdata := Mux(xip_mode,xip_w_data,in.pwdata)
    mspi.io.in.pstrb := Mux(xip_mode,0xF.U,in.pstrb)

    in.pready := Mux(xip_mode,state===s_valid,mspi.io.in.pready)
    val data_prev = Reg(UInt(32.W))//TODO:先读出来再翻转
    // data_prev:=mspi.io.in.prdata
    when(state===s_read_data){
    data_prev:=Cat(mspi.io.in.prdata(7,0),mspi.io.in.prdata(15,8),mspi.io.in.prdata(23,16),mspi.io.in.prdata(31,24))
    }
    in.prdata := data_prev//TODO
    in.pslverr := mspi.io.in.pslverr//TODO
    

    spi_bundle <> mspi.io.spi
  }
}
//101 0100 0000

    
    // //测试能不能用Wire来批量连接信号--显然不行
    // val xip_sig = Wire((new APBBundle(APBBundleParameters(addrBits = 32, dataBits = 32))))
    // xip_sig.pwrite := state===state===s_w_addr|s_w_con|s_w_ss
    // xip_sig.paddr := xip_addr
    // xip_sig.pprot :=1.U
    // xip_sig.pwdata:=xip_w_data
    // xip_sig.pstrb :=0xF.U
    // xip_sig.psel := true.B
    // xip_sig.penable :=true.B

    // val selected = MuxLookup(state,in)(
    //   List(
    //     s_common -> in,
    //     // s_w_ss -> xip_sig,
    //     // s_w_addr -> xip_sig,
    //     // s_w_con -> xip_sig,
    //     // s_wait_ready -> xip_sig,
    //     // s_valid ->xip_sig,
    //   )
    // )
  // val selectedGroup = Wire(new APBBundle(APBBundleParameters(addrBits = 32, dataBits = 32)))
  // selectedGroup := MuxCase(
  //   in, // 默认值
  //   Array(
  //     (state===s_w_ss) -> xip_sig,
  //     (state===s_w_addr) -> in
  //   )
  // )
    // mspi.io.in <> in
    // mspi.io.in <> selectedGroup
