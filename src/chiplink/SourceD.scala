// See LICENSE for license details.
package sifive.blocks.devices.chiplink

import chisel3._
import chisel3.util._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.util._

class SourceD(info: ChipLinkInfo) extends Module
{
  val io = IO(new Bundle {
    val d = Decoupled(new TLBundleD(info.edgeIn.bundle))
    val q = Flipped(Decoupled(UInt(info.params.dataBits.W)))
    // Used by E to find the txn
    val e_tlSink = Flipped(Valid(UInt(info.params.sinkBits.W)))
    val e_clSink = Output(UInt(info.params.clSinkBits.W))
  })

  // We need a sink id CAM
  val cam = Module(new CAM(info.params.sinks, info.params.clSinkBits))

  // Map ChipLink transaction to TileLink source
  val cl2tl = info.sourceMap.map(_.swap)
  val nestedMap = cl2tl.groupBy(_._1.domain).mapValues(_.map { case (TXN(_, cls), tls) => (cls, tls) })
  val muxes = Seq.tabulate(info.params.domains) { i =>
    info.mux(nestedMap.lift(i).getOrElse(Map(0 -> 0)))
  }

  // The FSM states
  val state = RegInit(0.U(2.W))
  val s_header   = 0.U(2.W)
  val s_sink     = 1.U(2.W)
  val s_data     = 2.U(2.W)

  private def hold(key: UInt)(data: UInt) = {
    val enable = state === key
    Mux(enable, data, RegEnable(data, enable))
  }

  //这里的抽取可以参考Parameters.scala
  // Extract header fields from the message
  val Seq(_, q_opcode, q_param, q_size, q_domain, q_source) =
    info.decode(io.q.bits).map(hold(s_header) _)

  // Extract sink from the optional second beat
  val q_sink = hold(s_sink)(io.q.bits(15, 0))

  val q_grant = q_opcode === TLMessages.Grant || q_opcode === TLMessages.GrantData
  val (_, q_last) = info.firstlast(io.q, Some(3.U))
  val d_first = RegEnable(state =/= s_data, io.q.fire)
  val s_maybe_data = Mux(q_last, s_header, s_data)

  when (io.q.fire) {
    switch (state) {
      is (s_header)   { state := Mux(q_grant, s_sink, s_maybe_data) }
      is (s_sink)     { state := s_maybe_data }
      is (s_data)     { state := s_maybe_data }
    }
  }

  // Look for an available sink
  val sink_ok = !q_grant || cam.io.alloc.ready
  val sink  = cam.io.key holdUnless d_first
  val stall = d_first && !sink_ok
  val xmit  = q_last || state === s_data

  io.d.bits.opcode  := q_opcode
  io.d.bits.param   := q_param(1,0)
  io.d.bits.size    := q_size
  //TDDO:有空再解决
  // 额，这似乎是sifive的开源代码，https://github.com/sifive/sifive-blocks/blob/master/src/main/scala/devices/chiplink/Parameters.scala
  //明天做一下diff
  //目前我approach：
  //muxes打印出来的结果是List(SourceD.muxes_0: Wire[UInt<1>[1]], SourceD.muxes_1: Wire[UInt<3>[8]],
  // SourceD.muxes_2: Wire[UInt<4>[8]], SourceD.muxes_3: Wire[UInt<1>[1]], SourceD.muxes_4: Wire[UInt<1>[1]],
  // SourceD.muxes_5: Wire[UInt<1>[1]], SourceD.muxes_6: Wire[UInt<1>[1]], SourceD.muxes_7: Wire[UInt<1>[1]])

  //而VecInit(muxes.map { m => m(0) }) 打印出来的结果是 SourceD_1.?: Wire[UInt<4>[8]]
  
  //猜想：要在哪个地方补齐，让所有 Source 都为 Wire[UInt<4>[8]]

  //可能要修改info.mux() --- 在

  //VecInit(muxes.map { m => m(narrow_q_source) }) 应该是一个包含8个4位宽的list,q_domain有3位正好选其中一个

  
  io.d.bits.source  := VecInit(muxes.map { m => m(q_source) })(q_domain)
  io.d.bits.sink    := Mux(q_grant, sink, 0.U)
  io.d.bits.denied  := q_param >> 2
  io.d.bits.data    := io.q.bits
  io.d.bits.corrupt := io.d.bits.denied && info.edgeIn.hasData(io.d.bits)

  io.d.valid := (io.q.valid && !stall) &&  xmit
  io.q.ready := (io.d.ready && !stall) || !xmit

  cam.io.alloc.valid := q_grant && d_first && xmit && io.q.valid && io.d.ready
  cam.io.alloc.bits  := q_sink

  // Free the CAM
  io.e_clSink := cam.io.data
  cam.io.free := io.e_tlSink
}
