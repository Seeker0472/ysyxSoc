file://<WORKSPACE>/src/device/SPI.scala
### java.lang.IndexOutOfBoundsException: 0

occurred in the presentation compiler.

presentation compiler configuration:
Scala version: 3.3.3
Classpath:
<HOME>/.cache/coursier/v1/https/repo1.maven.org/maven2/org/scala-lang/scala3-library_3/3.3.3/scala3-library_3-3.3.3.jar [exists ], <HOME>/.cache/coursier/v1/https/repo1.maven.org/maven2/org/scala-lang/scala-library/2.13.12/scala-library-2.13.12.jar [exists ]
Options:



action parameters:
offset: 3285
uri: file://<WORKSPACE>/src/device/SPI.scala
text:
```scala
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

    val s_idle :: s_common :: s_w_ss :: s_w_addr :: s_w_con :: s_wait_data :: s_valid :: s_reset :: Nil =
      Enum(8)
    val state = RegInit(s_common)
    val is_spi = mspi.io.in.paddr === BitPat("b0011????????????????????????????")
    state := MuxLookup(state, s_common)(
      List(
        s_idle ->Mux(mspi.io.psel,Mux(is_spi,s_w_ss,s_common),s_idle)
        s_common -> Mux(mspi.io.pready, s_idle, s_common),
        s_w_ss -> Mux(mspi.io.in.pready, s_w_addr, s_w_ss),
        s_w_addr -> Mux(mspi.io.in.pready, s_w_con, s_w_addr),
        s_w_con -> Mux(mspi.io.in.pready, s_wait_data, s_w_con),
        s_wait_data -> Mux(mspi.io.prdata(8,8)===0.U&&mspi.io.pready, s_valid, s_wait_data),//应该是查询中断/控制寄存器中
        s_valid -> Mux(in.pready, s_valid, s_wait_data)//in的ready信号(axi向spi发送的信号？)
      )
    )
    //生成addr(spi的寄存器地址)
    val xip_addr = MuxLookup(state, 0x10001000.U(32.W))(
      List(
        s_reset -> 0x10001018.U(32.W),
        s_w_ss -> 0x10001018.U(32.W),
        s_w_addr -> 0x10001004.U(32.W),
        s_w_con -> 0x10001010.U(32.W),
        s_wait_data -> 0x10001010.U(32.W),
        s_valid -> 0x10001000.U(32.W)
      )
    )
    //生成data(向spi的寄存器写数据(地址已经在上面指定))
    val addr_in=0.U(32.W)//TODO: Link TO　Ｉｎｐｕｔ
    // val is_write = state===s_w_addr|s_w_con|s_w_ss
    val xip_w_data = MuxLookup(state,0.U(32.W))(
      List(
        s_w_ss -> 1.U(32.W),
        s_w_addr -> Cat(0x03.U(8.W),addr_in(23,0)),
        s_w_con -> 0x540.U(32.W)
      )
    )

    //测试能不能用Wire来批量连接信号

    val xip_sig = Wire(Flipped(new APBBundle(APBBundleParameters(addrBits = 32, dataBits = 32))))
    xip_sig.pwrite := state===state===s_w_addr|s_w_con|s_w_ss
    xip_sig.paddr := xip_addr
    xip_sig.pprot :=1.U
    xip_sig.pwdata:=xip_w_data
    xip_sig.pstrb :=0xF.U
    xip_sig.psel := true.B
    xip_sig.penable :=true.B

    val selected = MuxLookup(state,Wire(Flipped(new APBBundle(APBBundleParameters(addrBits = 32, dataBits = 32)))))(@@)

    val send_data = null
    mspi.io.clock := clock
    mspi.io.reset := reset
    mspi.io.in <> in
    spi_bundle <> mspi.io.spi
  }
}

```



#### Error stacktrace:

```
scala.collection.LinearSeqOps.apply(LinearSeq.scala:131)
	scala.collection.LinearSeqOps.apply$(LinearSeq.scala:128)
	scala.collection.immutable.List.apply(List.scala:79)
	dotty.tools.dotc.util.Signatures$.countParams(Signatures.scala:501)
	dotty.tools.dotc.util.Signatures$.applyCallInfo(Signatures.scala:186)
	dotty.tools.dotc.util.Signatures$.computeSignatureHelp(Signatures.scala:94)
	dotty.tools.dotc.util.Signatures$.signatureHelp(Signatures.scala:63)
	scala.meta.internal.pc.MetalsSignatures$.signatures(MetalsSignatures.scala:17)
	scala.meta.internal.pc.SignatureHelpProvider$.signatureHelp(SignatureHelpProvider.scala:51)
	scala.meta.internal.pc.ScalaPresentationCompiler.signatureHelp$$anonfun$1(ScalaPresentationCompiler.scala:435)
```
#### Short summary: 

java.lang.IndexOutOfBoundsException: 0