/***************************************************************************************
* Copyright (c) 2020-2021 Institute of Computing Technology, Chinese Academy of Sciences
*
* XiangShan is licensed under Mulan PSL v2.
* You can use this software according to the terms and conditions of the Mulan PSL v2.
* You may obtain a copy of Mulan PSL v2 at:
*          http://license.coscl.org.cn/MulanPSL2
*
* THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY KIND,
* EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO NON-INFRINGEMENT,
* MERCHANTABILITY OR FIT FOR A PARTICULAR PURPOSE.
*
* See the Mulan PSL v2 for more details.
***************************************************************************************/

package xiangshan.frontend

import chipsalliance.rocketchip.config.Parameters
import chisel3._
import chisel3.stage.{ChiselGeneratorAnnotation, ChiselStage}
import chisel3.util._
import xiangshan._
import utils._
import chisel3.experimental.chiselName

import scala.math.min


trait FTBParams extends HasXSParameter with HasBPUConst {
  val numEntries = 2048
  val numWays    = 4
  val numSets    = numEntries/numWays // 512
  val tagSize    = 20
}

class FTBEntry (implicit p: Parameters) extends XSBundle with FTBParams {
  val valid       = Bool()
  val tag         = UInt(tagSize.W)

  val brOffset    = Vec(numBr, UInt(log2Up(FetchWidth*2).W))
  val brTargets    = Vec(numBr, UInt(VAddrBits.W))
  val brValids    = Vec(numBr, Bool())

  val jmpTarget   = UInt(VAddrBits.W)
  val jmpValid    = Bool()

  // Partial Fall-Through Address
  val pftAddr     = UInt(VAddrBits.W) // TODO: Modify only use lowerbits
  val carry       = Bool()

  val isCall      = Bool()
  val isRet       = Bool()
  val isJalr      = Bool()

  val oversize    = Bool()

  val last_is_rvc = Bool()

  // def getTarget(pred: Vec[UInt], pc: UInt): (UInt, UInt) = {
  //   val taken_mask = Cat(jmpValid, pred(1)(1), pred(0)(1))
  //   val target = pc + (FetchWidth*4).U

  //   when(taken_mask =/= 0.U) {
  //     target := PriorityMux(taken_mask, Seq(brTargets(0), brTargets(1), jmpTarget))
  //   }

  //   (taken_mask, target)
  // }
}

class FTBMeta(implicit p: Parameters) extends XSBundle with FTBParams {
  val writeWay = UInt(log2Up(numWays).W)
  val hit = Bool()
}

object FTBMeta {
  def apply(writeWay: UInt, hit: Bool)(implicit p: Parameters): FTBMeta = {
    val e = Wire(new FTBMeta)
    e.writeWay := writeWay
    e.hit := hit
    e
  }
}

class FTB(implicit p: Parameters) extends BasePredictor with FTBParams {
  val ftbAddr = new TableAddr(log2Up(numSets), numBr)

  val ftb = Module(new SRAMTemplate(new FTBEntry, set = numSets, way = numWays, shouldReset = true, holdRead = true, singlePort = true))

  val s1_idx = ftbAddr.getBankIdx(s1_pc)
  val s1_tag = ftbAddr.getTag(s1_pc)

  ftb.io.r.req.valid := io.s0_fire
  ftb.io.r.req.bits.setIdx := s1_idx

  io.in.ready := ftb.io.r.req.ready && !io.flush.valid
  // io.out.valid := RegEnable(RegNext(io.s0_fire), io.s1_fire) && !io.flush.valid
  io.out.valid := io.s2_fire && !io.flush.valid

  io.out.bits.resp.valids(1) := io.out.valid

  val s1_read = VecInit((0 until numWays).map(w =>
    ftb.io.r.resp.data(w)
  ))

  val s1_totalHits = VecInit((0 until numWays).map(b => s1_read(b).tag === s1_tag && s1_read(b).valid))
  val s1_hit = s1_totalHits.reduce(_||_)
  val s1_hit_way = PriorityEncoder(s1_totalHits)

  def allocWay(valids: UInt, meta_tags: UInt, req_tag: UInt) = {
    val randomAlloc = true
    if (numWays > 1) {
      val w = Wire(UInt(log2Up(numWays).W))
      val valid = WireInit(valids.andR)
      val tags = Cat(meta_tags, req_tag)
      val l = log2Up(numWays)
      val nChunks = (tags.getWidth + l - 1) / l
      val chunks = (0 until nChunks).map( i =>
        tags(min((i+1)*l, tags.getWidth)-1, i*l)
      )
      w := Mux(valid, if (randomAlloc) {LFSR64()(log2Up(numWays)-1,0)} else {chunks.reduce(_^_)}, PriorityEncoder(~valids))
      w
    } else {
      val w = WireInit(0.U)
      w
    }
  }
  val allocWays = VecInit((0 until numWays).map(b =>
    allocWay(VecInit(s1_read.map(w => w.valid)).asUInt,
      VecInit(s1_read.map(w => w.tag)).asUInt,
      s1_tag)))

  val writeWay = Mux(s1_hit, s1_hit_way, allocWays(0)) // TODO: allocWays is Vec

  val ftb_entry = s1_read(s1_hit_way)

  val brTargets = ftb_entry.brTargets
  val jmpTarget = ftb_entry.jmpTarget

  io.out.bits.resp := io.in.bits.resp_in(0)

  val s1_latch_target = Wire(UInt(VAddrBits.W))
  s1_latch_target := io.in.bits.resp_in(0).s1.preds.target
  when(s1_hit) {
    s1_latch_target := Mux((io.in.bits.resp_in(0).s1.preds.taken_mask.asUInt & ftb_entry.brValids.asUInt) =/= 0.U,
      PriorityMux(io.in.bits.resp_in(0).s1.preds.taken_mask.asUInt & ftb_entry.brValids.asUInt, ftb_entry.brTargets),
      Mux(ftb_entry.jmpValid, ftb_entry.jmpTarget, s1_pc + (FetchWidth*4).U))
  }

  val s1_latch_taken_mask = Wire(Vec(numBr+1, Bool()))
  s1_latch_taken_mask     := io.in.bits.resp_in(0).s1.preds.taken_mask
  s1_latch_taken_mask(0)  := ftb_entry.jmpValid

  val s1_latch_is_br         = ftb_entry.brValids
  val s1_latch_is_jal        = ftb_entry.jmpValid && !(ftb_entry.isJalr || ftb_entry.isCall ||ftb_entry.isRet)
  val s1_latch_is_jalr       = ftb_entry.isJalr
  val s1_latch_is_call       = ftb_entry.isCall
  val s1_latch_is_ret        = ftb_entry.isRet
  val s1_latch_call_is_rvc   = DontCare // TODO: modify when add RAS

  val s1_latch_pc            = s1_pc
  val s1_latch_hit           = s1_hit
  val s1_latch_meta          = FTBMeta(writeWay.asUInt(), s1_hit).asUInt()
  val s1_latch_ftb_entry     = ftb_entry

  // when (RegNext(s1_hit)) {
  io.out.bits.resp.s2.preds.taken_mask  := RegEnable(s1_latch_taken_mask, io.s1_fire)
  io.out.bits.resp.s2.preds.is_br       := RegEnable(s1_latch_is_br, io.s1_fire)
  io.out.bits.resp.s2.preds.is_jal      := RegEnable(s1_latch_is_jal, io.s1_fire)
  io.out.bits.resp.s2.preds.is_jalr     := RegEnable(s1_latch_is_jalr, io.s1_fire)
  io.out.bits.resp.s2.preds.is_call     := RegEnable(s1_latch_is_call, io.s1_fire)
  io.out.bits.resp.s2.preds.is_ret      := RegEnable(s1_latch_is_ret, io.s1_fire)

  io.out.bits.resp.s2.preds.target      := RegEnable(s1_latch_target, io.s1_fire)
  io.out.bits.resp.s2.pc                := RegEnable(s1_latch_pc, io.s1_fire) //s2_pc
  io.out.bits.resp.s2.hit               := RegEnable(s1_latch_hit, io.s1_fire)
  io.out.bits.resp.s2.meta              := RegEnable(s1_latch_meta, io.s1_fire)
  io.out.bits.resp.s2.ftb_entry         := RegEnable(s1_latch_ftb_entry, io.s1_fire)
  // }

  // override flush logic
  // io.out.bits.flush_out.valid := io.in.bits.resp_in(0).s1.preds.taken =/= io.in.bits.resp_in(0).s2.preds.taken ||
  //                       io.in.bits.resp_in(0).s1.preds.target =/= io.in.bits.resp_in(0).s2.preds.target
  // io.out.bits.flush_out.bits := io.in.bits.resp_in(0).s2.preds.target

  // Update logic
  val update = io.update.bits

  val u_pc = update.pc

  val u_meta = update.meta.asTypeOf(new FTBMeta)
  val u_way = u_meta.writeWay
  val u_idx = ftbAddr.getIdx(u_pc)
  val u_valid = RegNext(io.update.valid)
  val u_way_mask = UIntToOH(u_way)

  val ftb_write = WireInit(update.ftb_entry)

  ftb_write.valid := true.B
  ftb_write.tag   := ftbAddr.getTag(u_pc)

  ftb.io.w.apply(u_valid, ftb_write, u_idx, u_way_mask)
}
