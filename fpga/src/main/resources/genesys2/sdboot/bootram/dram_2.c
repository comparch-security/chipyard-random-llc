// See LICENSE for license details.

#include "uart.h"
#include "kprintf.h"
#include "platform.h"

//#define STEP_SIZE 4
#define STEP_SIZE 1024*16
//#define VERIFY_DISTANCE 2
#define VERIFY_DISTANCE 64


int main() {
  unsigned long waddr = 0;
  unsigned long raddr = 0;
  unsigned long long wkey = 0;
  unsigned long long rkey = 0;
  unsigned int i = 0;
  unsigned distance = 0;
  unsigned int disp = 0;

  uart_init();
  kputln("DRAM test program.");
  uint64_t *memory_base = (uint64_t *)(MEMORY_MEM_ADDR);

  wkey = (uint64_t)(memory_base + waddr);
  while(1) {
    for(i=0; i<STEP_SIZE; i++) {
      if((uint64_t)(memory_base + waddr) >= (MEMORY_MEM_ADDR + MEMORY_MEM_SIZE) - 0x0f) {
	kprintln("reach end  %lx", memory_base + waddr);
	while(1);
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
        kprintln("@%x", memory_base + raddr);
      }
      for(i=0; i<STEP_SIZE; i++) {
        unsigned long long rd = *(memory_base + raddr);
        if(rkey != rd) {
          uint64_t pc;
	  uint64_t sp;
          kprintln("Error! key %lx stored @%lx does not match with %lx", rd, memory_base+raddr, rkey);
	  asm volatile ("mv    %0, sp":  "=r" ((uint64_t)sp));
	  kprintln("this error may bacause sp=%lx is override?", sp);
	  asm volatile ("auipc %0,  0":  "=r" ((uint64_t)pc));
          kprintln("this error may bacause pc=%lx is override?", pc);
          while(1);
        }
        raddr = (raddr + 1);
        rkey = (uint64_t)(memory_base + raddr);
      }
    }
  }
}
