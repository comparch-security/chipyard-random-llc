// See LICENSE for license details.

#include <assert.h>
#include <stdlib.h>
#include <stdio.h>
#include "util.h"

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
  unsigned distance = 0;
  unsigned int disp = 0;

  printf("DRAM test program.");
  uint64_t *memory_base = (uint64_t *)0xb1000000;

  wkey = (uint64_t)(memory_base + waddr);
  while(1) {
    for(i=0; i<STEP_SIZE; i++) {
      if((uint64_t)(memory_base + waddr) >= (MEMORY_MEM_ADDR + MEMORY_MEM_SIZE)) {
	printf("reach end  %p", memory_base + waddr);
	return 0;
       }
      *(memory_base + waddr) = wkey;
      waddr = (waddr + 1);
      wkey = (uint64_t)(memory_base + waddr);
    }
    
    if(distance < VERIFY_DISTANCE) distance++;

    rkey = (uint64_t)(memory_base + raddr);
    if(distance == VERIFY_DISTANCE) {
      disp ++;
      if(disp == 1000) {
        disp = 0;
        printf("@%x", memory_base + raddr);
      }
      for(i=0; i<STEP_SIZE; i++) {
        unsigned long long rd = *(memory_base + raddr);
        if(rkey != rd) {
          uint64_t pc;
	  uint64_t sp;
          printf("Error! key %lx stored @%lx does not match with %lx", rd, memory_base+raddr, rkey);
	  asm volatile ("mv    %0, sp":  "=r" ((uint64_t)sp));
	  printf("this error may bacause sp=%lx is override?", sp);
	  asm volatile ("auipc %0,  0":  "=r" ((uint64_t)pc));
          printf("this error may bacause pc=%lx is override?", pc);
          return 1; //tohost!=0 means error 
        }
        raddr = (raddr + 1);
        rkey = (uint64_t)(memory_base + raddr);
      }
    }
  }
}
