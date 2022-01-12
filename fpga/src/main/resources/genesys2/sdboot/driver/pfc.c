#include <stdint.h>
#include <platform.h>
#include "pfc.h"

inline static void write_pfcc(uint64_t data) {
  asm volatile ("csrw 0x8e0, %0" :: "r"(data));
}

inline static void write_pfcm(uint64_t data) {
  asm volatile ("csrw 0x8e1, %0" :: "r"(data));
}

inline static uint64_t read_pfcc(void) {
  uint64_t data;
  asm volatile ("csrr %0, 0x8e0" : "=r"(data));
  return data;
}

inline static uint64_t read_pfcm(void) {
  uint64_t data;
  asm volatile ("csrr %0, 0x8e1" : "=r"(data));
  return data;
}

inline static uint64_t read_pfcr(void) {
  uint64_t data;
  asm volatile ("csrr %0, 0xce0" : "=r"(data));
  return data;
}

uint8_t bitmap_one(uint64_t bitmap) {
  uint8_t count = 0;
  while(bitmap > 0) {
    if(bitmap & 0x01) count ++;
    bitmap >>= 1;
  }
  return count;
}

void bitmap_order(pfccsr_t* pfccsr) {
  uint8_t i1=0, i2=pfccsr->rnum -1;
  for(; (pfccsr->m >> i1); i1++) { }
  for(i1--; i1 > i2; i1--) {
     if((pfccsr->m >> i1) & 0x01) {
       pfccsr->r[i1]   = pfccsr->r[i2];
       pfccsr->r[i2--] = 0;
    }
  }
}

uint8_t get_pfc(uint8_t managerid, uint8_t rpage, uint64_t bitmap, pfccsr_t* pfccsr) {
  uint8_t  reqnum  = bitmap_one(bitmap);
  uint8_t  recnum, ramtype, timeout;
  pfccsr->rnum = 0;
  pfccsr->c    = (managerid << PFC_C_MANAGERID) + ((rpage & PFC_C_RPAGE_MASK) << PFC_C_PAGE) + 1;
  write_pfcm(bitmap);
  write_pfcc(pfccsr->c);          //after trigger and receive we must read prcr within 32 instructions retired
  for(recnum  = 0, timeout = 0, ramtype = (rpage >> PFC_C_PAGE_LEN) & 0x01; timeout < 10; ) {
    pfccsr->c  = read_pfcc();
    if(pfccsr->c & PFC_C_EMPTY_BIT) { timeout ++; } 
    else {
      pfccsr->r[recnum] = read_pfcr();
      pfccsr->raddall = pfccsr->raddall + pfccsr->r[recnum];
      recnum ++; 
      timeout = 0;
    }
    if(recnum >= 64) break;
    if(!ramtype && (recnum >= reqnum)) break;
  }
  pfccsr->c  = read_pfcc();
  pfccsr->m  = read_pfcm();
  pfccsr->rnum = recnum;
  if(pfccsr->c & PFC_C_TIMEOUT_BIT  )  { return PFC_ERR_TIMEOUT;    } //hardware time out
  if(pfccsr->c & PFC_C_READERROR_BIT)  { return PFC_ERR_READ;       }
  if(pfccsr->c & PFC_C_INTERRUPT_BIT)  { return PFC_ERR_INTERRUPT;  }
  if(ramtype)  { pfccsr->ramaddr = bitmap;  }
  if(!ramtype) { bitmap_order(pfccsr);      }
  return recnum;
}
/*
pfccsr_t CoreEG0[PFC_CORES];      //core event group0
pfccsr_t L1ISetmiss[PFC_CORES];
pfccsr_t L1DSetmiss[PFC_CORES];
pfccsr_t L1DSetWB[PFC_CORES];     //write back

pfccsr_t L2EG0[PFC_L2BANKS];        //l2 event group0
pfccsr_t L2Setmiss[PFC_L2BANKS];
pfccsr_t L2SetWB[PFC_L2BANKS];      //write back

void get_tile_pfc(uint8_t id) {
  uint8_t i;

  get_pfc(PFC_TILE0_MANAGER,     PFC_CORE_EG0_RPAGE, 0xffffffffffffffff, &CoreEG0[id]);

  L1ISetmiss[id].rtotal=0;
  L1ISetmiss[id].ramaddr=0;
  for(i=0; i<(PFC_ISETS>>6); i++) {
    get_pfc(PFC_TILE0_MANAGER,   PFC_L1ISM_RPAGE,    L1ISetmiss[id].ramaddr+i,   &L1ISetmiss[id]);
  }

  L1DSetmiss[id].rtotal=0;
  L1DSetmiss[id].ramaddr=0;
  for(i=0; i<(PFC_DSETS>>6); i++) {
    get_pfc(PFC_TILE0_MANAGER,   PFC_L1DSM_RPAGE,    L1DSetmiss[id].ramaddr+i,   &L1DSetmiss[id]);
  }

  L1DSetWB[id].rtotal=0;
  L1DSetWB[id].ramaddr=0;
  for(i=0; i<(PFC_DSETS>>6); i++) {
    get_pfc(PFC_TILE0_MANAGER,   PFC_L1DSWB_RPAGE,   L1DSetWB[id].ramaddr+i,     &L1DSetWB[id]);
  }
}

void get_l2_pfc(uint8_t id) {
  uint8_t i;

  get_pfc(PFC_L2BANK0_MANAGER,   PFC_L2_RPAGEP0,     0xffffffffffffffff,   &L2EG0[id]);

  L1ISetmiss[id].rtotal=0;
  L1ISetmiss[id].ramaddr=0;
  for(i=0; i<(PFC_L2SETS>>6); i++) {
    get_pfc(PFC_TILE0_MANAGER,   PFC_L1ISM_RPAGE,    L1ISetmiss[id].ramaddr+i,   &L2Setmiss[id]);
  }

  L2SetWB[id].rtotal=0;
  L2SetWB[id].ramaddr=0;
  for(i=0; i<(PFC_L2SETS>>6); i++) {
    get_pfc(PFC_TILE0_MANAGER,   PFC_L1DSM_RPAGE,    L1DSetmiss[id].ramaddr+i,   &L2SetWB[id]);
  }
}
*/
char COEVENTG0_NAME[37][30] = {
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
  "cft_misprediction     ", //event26: controlflow_target_misprediction
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

char TLEVENTG_NAME[49][22] = {
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
  "b_Get              ",  //event17
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

char L2EVENTG0_NAME[16][22] = {
    //acquire chancel
  "r_Finish          ",  //event0
  "r_nop             ",  //event1
  "r_busy            ",  //event2
  "r_swap            ",  //event3
  "r_evcit           ",  //event4
  "r_ebusy           ",  //event5
  "r_pause           ",  //event6
  "r_atdetec         "   //event7
};
