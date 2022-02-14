// See LICENSE for license details.

#include <assert.h>
#include <stdlib.h>
#include <stdio.h>
#include "util.h"

//#define STEP_SIZE 4
#define STEP_SIZE 1024
//#define VERIFY_DISTANCE 2
#define VERIFY_DISTANCE 4
#define MEMORY_MEM_SIZE 0x40000000
#define MEMORY_MEM_ADDR 0x80000000
#define MEM_END  (MEMORY_MEM_ADDR + MEMORY_MEM_SIZE)
#define TEST_START_ADDR 0x81000000
#define RESET_ADDR 0x80000000

static struct status {
  uint64_t pc;
  uint64_t sp;
  uint64_t s0;
  uint8_t fails;
  uint8_t resets;
} __attribute__((aligned (32))) s;

struct control {
  uint64_t lfsr;
  uint64_t rd;
  uint8_t  orde;
  uint32_t incr;
  uint32_t i;
  uint8_t  j;
  uint16_t disp;
  uint8_t  distance;
  uint64_t pzone[2]; //protect_zone
}__attribute__((aligned (64))); //occupy one cache block

void reset(void) {
  s.resets ++;
  printf("\n");
  printf("Resets: %d \n");
  asm volatile ("mv a0, %0": :"r" ((uint64_t)(RESET_ADDR)) : "a0");
  asm volatile ("jr a0");
  __builtin_unreachable();
}

inline uint64_t lfsr64(uint64_t d) {
  // x^64 + x^63 + x^61 + x^60 + 1
  return (d >> 1) | (((d >> (64-64)) ^
                      (d >> (64-63)) ^
                      (d >> (64-61)) ^
                      (d >> (64-60)) ^ 1) << 63);
}


int main(void) {
  struct control c;
  register uint64_t* addr_r;
  register uint64_t* addr_w0;
  register uint64_t* addr_w1;
  register uint64_t* addr_w2;
  register uint64_t* addr_w3;
  register uint64_t* addr_w4;
  register uint64_t* addr_w5;
  register uint64_t* addr_w6;
  register uint64_t* addr_w7;
  #define RKEY (uint64_t)(addr_r)
  #define WKEY (uint64_t)(addr_w0)
  c.lfsr = 2;
  c.orde = 0;
  c.incr = 0;
  c.disp = 0;
  c.distance = 0;

  printf("DRAM test program.\n");
  asm volatile ("auipc %0,  0":  "=r" ((uint64_t)s.pc));
  asm volatile ("mv    %0, sp":  "=r" ((uint64_t)s.sp));
  asm volatile ("mv    %0, s0":  "=r" ((uint64_t)s.s0));
  printf("REG: pc      %x sp       %x s0    %x\n", s.pc, s.sp, s.s0 );
  printf("MAP: status @%x control @%x\n", &s,   &c);
  printf("TEST:    @%x - @%x\n", TEST_START_ADDR, MEM_END);
  addr_r  =  (uint64_t *)(TEST_START_ADDR);
  addr_w0 =  (uint64_t *)(TEST_START_ADDR);
  while(1) {
    //write
    for(c.i=0; c.i<STEP_SIZE; c.i++) {
      c.orde = c.lfsr+c.orde;
      c.lfsr = lfsr64(c.lfsr);
      c.incr = c.lfsr & (STEP_SIZE - 1);
      addr_w1 = addr_w0 + 1;
      addr_w2 = addr_w1 + 1;
      addr_w3 = addr_w2 + c.i;
      addr_w4 = addr_w3 + c.i;
      addr_w5 = addr_w4 + c.incr;
      addr_w6 = addr_w5 + c.incr;
      addr_w7 = addr_w6 + c.incr;
      if((uint64_t)addr_w0 >= MEM_END) { break;                          }
      if((uint64_t)addr_w1 >= MEM_END) { addr_w1 = (uint64_t *)MEM_END-1;}
      if((uint64_t)addr_w2 >= MEM_END) { addr_w2 = (uint64_t *)MEM_END-1;}
      if((uint64_t)addr_w3 >= MEM_END) { addr_w3 = (uint64_t *)MEM_END-1;}
      if((uint64_t)addr_w4 >= MEM_END) { addr_w4 = (uint64_t *)MEM_END-1;}
      if((uint64_t)addr_w5 >= MEM_END) { addr_w5 = (uint64_t *)MEM_END-1;}
      if((uint64_t)addr_w6 >= MEM_END) { addr_w6 = (uint64_t *)MEM_END-1;}
      if((uint64_t)addr_w7 >= MEM_END) { addr_w7 = (uint64_t *)MEM_END-1;}

      //#define RANDOM_WRITE
      #ifdef  RANDOM_WRITE
      for(c.j=0; c.j<=7; c.j++) { 
         c.orde=(c.orde+1)&0x07;
         switch (c.orde) {
           case 1:  *(addr_w1) = WKEY; break;
           case 2:  *(addr_w2) = WKEY; break;
           case 3:  *(addr_w3) = WKEY; break;
           case 4:  *(addr_w4) = WKEY; break;
           case 5:  *(addr_w5) = WKEY; break;
           case 6:  *(addr_w6) = WKEY; break;
           case 7:  *(addr_w7) = WKEY; break;
           default: *(addr_w0) = WKEY; break;
         }
       }
      #else
      *(addr_w3) = WKEY;
      *(addr_w4) = WKEY;
      *(addr_w0) = WKEY;
      *(addr_w1) = WKEY;
      *(addr_w2) = WKEY;
      *(addr_w5) = WKEY;
      *(addr_w6) = WKEY;
      *(addr_w7) = WKEY;
      #endif
      addr_w0 ++;
    }

    if(c.distance < VERIFY_DISTANCE) c.distance++;

    //check
    if(c.distance == VERIFY_DISTANCE) {
      if(c.disp++==0) { printf("CHECK:\n"); } if(c.disp >= 1000 || c.disp == 1) { c.disp = 2; printf("@%x\n", addr_r); }
      for(c.i=0; c.i<STEP_SIZE; c.i++) {
        c.rd  = *(addr_r);
        *(addr_r) = 0;
        if(RKEY != c.rd)                 { printf("Error! key %x stored @%x\n" , c.rd, addr_r); return 1; } //tohost!=0 means error
        addr_r++;
        if((uint64_t)addr_r >= MEM_END)  { printf("Reach end  %x\n", addr_r);                   reset();  } //test again
      }
    }
  } //while(1)
   __builtin_unreachable();
}
