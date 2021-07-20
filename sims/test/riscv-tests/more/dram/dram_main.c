// See LICENSE for license details.

#include <assert.h>
#include <stdlib.h>
#include <stdio.h>
#include "util.h"

unsigned long long lfsr64(unsigned long long d) {
  // x^64 + x^63 + x^61 + x^60 + 1
  unsigned long long bit = 
    (d >> (64-64)) ^
    (d >> (64-63)) ^
    (d >> (64-61)) ^
    (d >> (64-60)) ^
    1;
  return (d >> 1) | (bit << 63);
}

//#define STEP_SIZE 4
#define STEP_SIZE 1024*16
//#define VERIFY_DISTANCE 2
#define VERIFY_DISTANCE 16
#define MEMORY_MEM_SIZE 0x40000000
#define MEMORY_MEM_ADDR 0x80000000


int main() {
  unsigned long waddr = 0;
  unsigned long raddr = 0;
  unsigned long long wkey = 0;
  unsigned long long rkey = 0;
  unsigned int i = 0;
  unsigned int error_cnt = 0;
  unsigned distance = 0;

  printf("DRAM test program.\n");
  uint64_t *memory_base= (uint64_t *)0xb1000000;

  while(1) {
    printf("Write block @%p using key %p\n",memory_base+waddr, wkey);
    for(i=0; i<STEP_SIZE; i++) {
      if((uint64_t)(memory_base + waddr) >= (MEMORY_MEM_ADDR + MEMORY_MEM_SIZE)) {
	printf("reach end  %p\n", memory_base + waddr);
	return 0;
       }
      *(memory_base + waddr) = wkey;
      waddr = (waddr + 1);
      wkey = lfsr64(wkey);
    }
    
    if(distance < VERIFY_DISTANCE) distance++;

    if(distance == VERIFY_DISTANCE) {
      printf("Check block @%p using key %p\n", memory_base + raddr, rkey);
      for(i=0; i<STEP_SIZE; i++) {
        unsigned long long rd = *(memory_base + raddr);
        if(rkey != rd) {
          uint64_t pc;
	  uint64_t sp;
          printf("Error! key %p stored @%p does not match with %p\n", rd, memory_base+raddr, rkey);
	  asm volatile ("mv    %0, sp":  "=r" ((uint64_t)sp));
	  printf("this error may bacause sp=%lx is override?\n", sp);
	  asm volatile ("auipc %0,  0":  "=r" ((uint64_t)pc));
          printf("this error may bacause pc=%lx is override?\n", pc);
          error_cnt++;
          return 1; //tohost!=0 means error 
        }
        raddr = (raddr + 1);
        rkey = lfsr64(rkey);
        if(error_cnt > 10) return 1;;
      }
    }
  }
}
