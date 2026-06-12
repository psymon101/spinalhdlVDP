package spinalhdlvdp

import spinal.core._
import spinal.core.sim._

/** B.2 diagnostic: dump the FIFO push stream for one line-0 fetch to find the
  * kind/data mistag. Expect 80 bitmap pushes (kind=0, data low byte ~0x10) then
  * 80 attr pushes (kind=1, data ~0x20). Any bitmap-kind push carrying 0x20 data
  * is the bug. */
object BitmapRowFetchPushDumpSim extends App {
  Config.sim.compile {
    val sdramCd = ClockDomain.external("sdram", frequency = FixedFrequency(40500000 Hz))
    BitmapRowFetch(sdramCd, skipSdramInit = true)
  }.doSim { dut =>
    dut.clockDomain.forkStimulus(period = 16)
    dut.sdramCd.forkStimulus(period = 10)
    dut.io.sdramDout #= 0; dut.io.sdramDout32 #= 0; dut.io.sdramDataReady #= false
    dut.io.sdramBusy #= false; dut.io.fetchGrant #= false; dut.io.fetchLine #= 0
    dut.io.col #= 0; dut.io.enable #= false; dut.io.directColor #= false; dut.io.tileBootDone #= false
    dut.io.bitmapBase #= 0x100000; dut.io.attrBase #= 0x200000
    dut.io.bitmapStride #= 512; dut.io.attrStride #= 512; dut.io.bitmapHeight #= 240

    fork {
      while (true) {
        if (dut.io.sdramRd.toBoolean) {
          val addr = dut.io.sdramAddr.toLong.toInt
          dut.sdramCd.waitSampling(5)
          def b(o: Int): Long = { val a = addr + o; ((a ^ (a >> 8) ^ (a >> 16)) & 0xFF).toLong }
          dut.io.sdramDout32 #= b(0) | (b(1) << 8) | (b(2) << 16) | (b(3) << 24)
          dut.io.sdramDataReady #= true; dut.sdramCd.waitSampling(); dut.io.sdramDataReady #= false
        } else dut.sdramCd.waitSampling()
      }
    }

    // Monitor: log each push (pushPending 1->0 = push.fire) with kind/idx/data.
    var pushCount = 0
    fork {
      var prev = false
      while (pushCount < 170) {
        val pp = dut.sd.pushPending.toBoolean
        if (prev && !pp) {  // falling edge = push fired this cycle (entry consumed)
          val k = dut.sd.pendingKind.toBoolean
          val idx = dut.sd.pendingIdx.toInt
          val data = dut.sd.pendingData.toLong & 0xFFFFFFFFL
          // log only around the bitmap->attr boundary (78..86) and first attrs
          if ((pushCount >= 78 && pushCount <= 92))
            println(f"[sim] push#$pushCount%-3d kind=${if(k)"ATTR" else "BMP "} idx=$idx%3d data=0x$data%08X")
          pushCount += 1
        }
        prev = pp
        dut.sdramCd.waitSampling()
      }
    }

    // Monitor bitmap-buffer writes (pixel domain). Should be exactly 320, all
    // bitmap data. Any with attr-range data, or count > 320, is the bug.
    var bmWrites = 0; var bmWritesAttrData = 0
    fork {
      while (true) {
        if (dut.dbgBmWrEn.toBoolean) {
          val d = dut.dbgBmWrData.toInt
          if (bmWrites < 6) println(f"[sim] bmWrite#$bmWrites data=0x$d%02X (bitmap idx $bmWrites expects 0x${(0x100000+bmWrites)^((0x100000+bmWrites)>>8)^((0x100000+bmWrites)>>16) & 0xFF}%02X)")
          bmWrites += 1
          if (d >= 0x20 && d < 0x40) bmWritesAttrData += 1
        }
        dut.clockDomain.waitSampling()
      }
    }

    dut.sdramCd.waitSampling(10); dut.clockDomain.waitSampling(10)
    dut.io.enable #= true; dut.io.directColor #= true; dut.io.tileBootDone #= true
    var t = 2000
    while (!dut.io.bootDone.toBoolean && t > 0) { dut.sdramCd.waitSampling(); t -= 1 }
    dut.io.fetchLine #= 0
    dut.io.fetchGrant #= true; dut.clockDomain.waitSampling(4); dut.io.fetchGrant #= false
    dut.sdramCd.waitSampling(2000); dut.clockDomain.waitSampling(500)
    // SINGLE-fetch buffer read (no concurrency). bitmap idx p source = 0x100000+p,
    // attr idx p = 0x200000+p, with the (a^a>>8^a>>16)&0xFF signature.
    def sig(a: Int) = ((a ^ (a >> 8) ^ (a >> 16)) & 0xFF)
    var bad = 0
    for (p <- Seq(0, 1, 4, 16, 100, 200, 319)) {
      dut.io.col #= p * 2; dut.clockDomain.waitSampling(); sleep(1)
      val bm = dut.io.bitmapByte.toInt; val at = dut.io.attrByte.toInt
      val ebm = sig(0x100000 + p); val eat = sig(0x200000 + p)
      val ok = bm == ebm && at == eat
      if (!ok) bad += 1
      println(f"[sim] read p=$p%3d bitmap got=0x$bm%02X exp=0x$ebm%02X | attr got=0x$at%02X exp=0x$eat%02X ${if(ok)"OK" else "<<< MISMATCH"}")
    }
    println(if (bad == 0) "[sim] SINGLE-FETCH BUFFER: correct -> bug is multi-fetch/harness"
            else "[sim] SINGLE-FETCH BUFFER: WRONG -> pop-expander/readAsync bug")
    println(f"[sim] bitmapLineBuf writes total=$bmWrites (expect 320); with attr-range data=$bmWritesAttrData (expect 0)")
    println("[sim] BitmapRowFetchPushDumpSim done")
  }
}
