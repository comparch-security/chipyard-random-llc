/* Copyright (c) 2016 by the author(s)
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 *
 * ============================================================================
 *
 * Author(s):
 *   Stefan Wallentowitz <stefan@wallentowitz.de>
 */

#include "cli.h"
#include "unistd.h"
#include <assert.h>

typedef struct pfcp {      //pfc page
   uint8_t    id;          //page id
   uint8_t    sel;         //need read the page?
   uint8_t    disp;        //need display the page?
   uint64_t*  pfcr;
   uint64_t   sum;         // sum pfcr if need
   char       *pname;      //page name
   char      (*ename)[32]; //event name
   uint64_t  nevents;
} pfcp_t;

typedef struct pfcm {   //pfc manager
   uint8_t    id;      //manager id
   char       *name;
   uint8_t    npages;
   pfcp_t*    page[32]; //support 32 pages at most
   uint64_t*  pfcr;
   uint64_t   nevents;
} pfcm_t;

typedef struct pfctop {
  uint8_t    nmans;
  pfcm_t*    man[8];
} pfctop_t;

pfcp_t page_core;
pfcp_t page_ISmiss; //I Set miss
pfcp_t page_DSmiss;
pfcp_t page_DSWB;
pfcp_t page_L2ITL;
pfcp_t page_L2OTL;
pfcp_t page_L2Smiss;
pfcp_t page_L2SWB;

pfcm_t man_Tile;
pfcm_t man_L2;

pfctop_t pfc;

uint8_t pfcswiched = 0;
uint16_t memid = 3;
void cd_pfcstruct(uint8_t c);   //1:cread or 2:delete
void nameevents(void);
void selnopage(void);
void selallpage(void);

void osd_open_mempfc(struct osd_context *ctx) {
  uint16_t addr = osd_modid2addr(ctx, memid);
  if(!pfcswiched) {
    osd_reg_write16(ctx, addr, 0x203, 1);
    cd_pfcstruct(1);
    pfcswiched = 1;
  }
}

void osd_close_mempfc(struct osd_context *ctx, uint16_t modid) {
  memid = modid;
  uint16_t addr = osd_modid2addr(ctx, memid);
  osd_reg_write16(ctx, addr, 0x203, 0);
  //cd_pfcstruct(2);
  pfcswiched = 0;
}

uint32_t osd_mempfc_addr(uint8_t mid, uint8_t pid) {
  uint16_t bitmap = 0x0000;
  /*if(pid&0x80) {  //ram page
    bitmap = 0; 
    //bitmap &= 0xfffe; //if req the ram page the lowest bits in osd_pfc_bimap is always 0
  } else {        //reg page
    //bitmap |= 0x0001; //if req the reg page the lowest 4 bits in osd_pfc_bimap is always 1
  }*/
  return ((((uint32_t)mid << 8) + (uint32_t)pid) << 16) + (uint16_t)bitmap;
}

void osd_memory_pfc(struct osd_context *ctx, uint8_t sel) {
  pfcm_t* man;
  pfcp_t* page; 
  uint16_t mid,pid,enu; //managerID, pageID, eventnumber
  uint16_t isrampage;
  uint32_t memaddr;
  uint64_t rv;
  osd_open_mempfc(ctx);
  selnopage();
  
  //sel
  switch (sel) {
    case 1:   page_core.sel=1;  break;
    case 2:   page_L2ITL.sel=1; page_L2OTL.sel=1; break;
    case 3:   page_core.sel=1;  page_L2ITL.sel=1; page_L2OTL.sel=1; break;
    default:  selallpage(); break;
  }

  //read
  for(mid=0; mid<pfc.nmans; mid++) {                //managers
    man = pfc.man[mid];
    for(pid=0; pid<man->npages; pid++) {            //manager pages
      page = man->page[pid];
      if(page->sel) {
        memaddr = osd_mempfc_addr(man->id, page->id);
        rv = osd_memory_read(ctx, memid, memaddr, (uint8_t*)page->pfcr, (page->nevents << 3));
        if(rv) printf("memory_pfc:error\n");
      }
    }
  }

  //sum
  for(mid=0; mid<pfc.nmans; mid++) {            //managers
    man = pfc.man[mid];
    for(pid=0; pid<man->npages; pid++) {          //manager pages
      page = man->page[pid];
      isrampage = page->id & 0x80;
      if(page->sel) {
        page->sum = 0;
        if(isrampage!=0) {
         for(enu=0; enu<page->nevents; enu++) {   //page events
            page->sum += page->pfcr[enu];
          }  
        }
      }
    }
  }

  //display
  for(mid=0; mid<pfc.nmans; mid++) {                //managers
    man = pfc.man[mid];
    for(pid=0; pid<man->npages; pid++) {            //manager pages
      page = man->page[pid];
      isrampage = page->id & 0x80;
      if(page->sel) {
        if(page->disp) {
          for(enu=0; enu<page->nevents; enu++) {  //page events
            printf("%s_", man->name);
            if(isrampage!=0)  printf("%s: %ld\n", page->pname, page->pfcr[enu]);
            else              printf("%s_%s: %ld\n", page->pname, page->ename[enu], page->pfcr[enu]);      
          }
        }
        //if(isrampage!=0) printf("%s_%s_sum: %ld\n", man->name, page->pname, page->sum);
      }
    }
  }
}

void selnopage(void) {
  pfcm_t* man;
  pfcp_t* page; 
  uint16_t mid,pid; //managerID, pageID, eventnumber

  for(mid=0; mid<pfc.nmans; mid++) {                //managers
    man = pfc.man[mid];
    for(pid=0; pid<man->npages; pid++) {
      page = man->page[pid];
      page->sel = 0;
    }
  }
}

void selallpage(void) {
  pfcm_t* man;
  pfcp_t* page; 
  uint16_t mid,pid; //managerID, pageID, eventnumber

  for(mid=0; mid<pfc.nmans; mid++) {                //managers
    man = pfc.man[mid];
    for(pid=0; pid<man->npages; pid++) {
      page = man->page[pid];
      page->sel = 1;
    }
  }
}

void cd_pfcstruct(uint8_t c) {  //1:cread or 2:delete
  static  uint8_t created=0;
  uint8_t i,j;
  if(!created && c==1) {
    page_core.id          = 0x00;
    page_core.disp        = 1;

    page_ISmiss.id        = 0x80;
    page_ISmiss.disp      = 0;

    page_DSmiss.id        = 0x81;
    page_DSmiss.disp      = 0; 

    page_DSWB.id          = 0x82;
    page_DSWB.disp        = 0;   

    page_L2ITL.id         = 0x02;
    page_L2ITL.disp       = 1;   

    page_L2OTL.id         = 0x03;
    page_L2OTL.disp       = 1;

    page_L2Smiss.id       = 0x80;
    page_L2Smiss.disp     = 0;   

    page_L2SWB.id         = 0x81;
    page_L2SWB.disp       = 0;

    nameevents();

    man_Tile.id          = 0x00;
    man_Tile.name        = "Tile";
    man_Tile.npages      = 4;
    man_Tile.nevents     = 0;
    man_Tile.page[0]     = &page_core;
    man_Tile.page[1]     = &page_ISmiss;
    man_Tile.page[2]     = &page_DSmiss;
    man_Tile.page[3]     = &page_DSWB;

    man_L2.id            = 0x08;
    man_L2.name          = "L2";
    man_L2.npages        = 4;
    man_L2.nevents       = 0;
    man_L2.page[0]       = &page_L2ITL;
    man_L2.page[1]       = &page_L2OTL;
    man_L2.page[2]       = &page_L2Smiss;
    man_L2.page[3]       = &page_L2SWB;
    
    pfc.nmans    = 2;
    pfc.man[0]   = &man_Tile;
    pfc.man[1]   = &man_L2;
    for(i=0; i<pfc.nmans; i++) {
     pfc.man[i]->nevents = 0;
     for(j=0; j<pfc.man[i]->npages; j++) {
       pfc.man[i]->nevents += pfc.man[i]->page[j]->nevents;
     }
     pfc.man[i]->pfcr          = malloc(8*(pfc.man[i]->nevents+1));
     pfc.man[i]->page[0]->pfcr = pfc.man[i]->pfcr;
     for(j=1; j<pfc.man[i]->npages; j++) {
       pfc.man[i]->page[j]->pfcr = pfc.man[i]->page[j-1]->pfcr + pfc.man[i]->page[j-1]->nevents;
     }
    }
    selnopage();
    created=1;
  }
  if(created && c==2){
    for(i=0; i<pfc.nmans; i++) {
      free(pfc.man[i]->pfcr);
    }
  } 

}

void nameevents(void) {
  static char COEVENTG0_NAME[37][32] = {
    "cycle                 ", //event0
    "instruction           ", //event1
    "exception             ", //event2
    "load                  ", //event3
    "store                 ", //event4
    "amo                   ", //event5
    "system                ", //event6
    "arith                 ", //event7
    "branch                ", //event8
    "jal                   ", //event9
    "jalr                  ", //event10
     //("usingMulDiv)
    "mul                   ", //event11
    "div                   ", //event12
     //("usingFPU)
    "fp_load               ", //event13
    "fp_store              ", //event14
    "fp_add                ", //event15
    "fp_mul                ", //event16
    "fp_muladd             ", //event17
    "fp_divsqrt            ", //event18
    "fp_other              ", //event19
    
    "load_use_interlock    ", //event20
    "long_latency_interlock", //event21
    "csr_interlock         ", //event22
    "Iblocked              ", //event23
    "Dblocked              ", //event24
    "branch_misprediction  ", //event25
    "cft_misprediction     ", //event26: controlflow_tarsel_misprediction
    "flush                 ", //event27
    "replay                ", //event28
    //(usingMulDiv)
    "muldiv_interlock      ", //event29
    //(usingFPU)  
    "fp_interlock          ", //event30
    
    "Imiss                 ", //event31
    "Dmiss                 ", //event32
    "Drelease              ", //event33
    "ITLBmiss              ", //event34
    "DTLBmiss              ", //event35
    "L2TLBmiss             "  //event36
  };

  static char TLEVENTG_NAME[49][32] = {
    //a: Acquire channel
    "a_Done             ",  //event0
    "a_PutFullData      ",  //event1
    "a_PutPartialData   ",  //event2
    "a_ArithmeticData   ",  //event3
    "a_LogicalData      ",  //event4
    "a_Get              ",  //event5
    "a_Hint             ",  //event6
    "a_AcquireBlock     ",  //event7
    "a_AcquirePerm      ",  //event8
    "a_Blocked          ",  //event9
    "a_Err0             ",  //event10
    "a_Err1             ",  //event11
    //b: Probe channel
    "b_Done             ",  //event12
    "b_PutFullData      ",  //event13
    "b_PutPartialData   ",  //event14
    "b_ArithmeticData   ",  //event15
    "b_LogicalData      ",  //event16
    "b_sel              ",  //event17
    "b_Hint             ",  //event18
    "b_Probe            ",  //event19
    "b_Blocked          ",  //event20
    "b_Err0             ",  //event21
    "b_Err1             ",  //event22
    //c: Release channel
    "c_Done             ",  //event23
    "c_AccessAck        ",  //event24
    "c_AccessAckData    ",  //event25
    "c_HintAck          ",  //event26
    "c_ProbeAck         ",  //event27
    "c_ProbeAckData     ",  //event28
    "c_Release          ",  //event29
    "c_ReleaseData      ",  //event30
    "c_Blocked          ",  //event31
    "c_Err0             ",  //event32
    "c_Err1             ",  //event33
    //d: Grant channel
    "d_Done             ",  //event34
    "d_AccessAck        ",  //event35
    "d_AccessAckData    ",  //event36
    "d_HintAck          ",  //event37
    "d_Grant            ",  //event38
    "d_GrantData        ",  //event39
    "d_ReleaseAck       ",  //event40
    "d_Blocked          ",  //event41
    "d_Err0             ",  //event42
    "d_Err1             ",  //event43
    //e: Finish channe
    "e_Done             ",  //event44
    "e_GrantAck         ",  //event45
    "e_Blocked          ",  //event46
    "e_Err0             ",  //event47
    "e_Err1             "   //event48
  };

  page_core.pname       = "Core";
  page_core.ename       = COEVENTG0_NAME;
  page_core.nevents     = 37;

  page_ISmiss.pname     = "Imiss_Set";
  page_ISmiss.nevents   = 64;

  page_DSWB.pname       = "DEV_Set";
  page_DSWB.nevents     = 64;

  page_DSmiss.pname     = "Dmiss_Set";
  page_DSmiss.nevents   = 64;

  page_L2ITL.pname      = "inner_TLink";
  page_L2ITL.ename      = TLEVENTG_NAME;
  page_L2ITL.nevents    = 49;

  page_L2OTL.pname      = "outer_TLink";
  page_L2OTL.ename      = TLEVENTG_NAME;
  page_L2OTL.nevents    = 49;

  page_L2SWB.pname      = "EV_Set";
  page_L2SWB.nevents    = 1024;

  page_L2Smiss.pname    = "miss_Set";
  page_L2Smiss.nevents  = 1024;
}
