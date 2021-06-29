// See LICENSE for license details.

#include "uart.h"
#include "kprintf.h"
#include "platform.h"

const uint64_t pc = (uint64_t)(MEMORY_MEM_ADDR);
int main() {
  int i;
  uint64_t *p = (uint64_t *)(pc);
  uart_init();
  kprintln("BOOTRAM: jump to ddr");
  kprintln("-----------addr-----------data-------------------");
  kprintln("--- %lx -- %lx ---", p, *p);
  for(i=0;i<16;i++) {
     p = p + 256;
     kprintln("--- %lx -- %lx ---", p, *p);
  }
  kprintln("-----------now-----------jump-------------------");
  asm volatile ("mv a0, %0": :"r" ((uint64_t)pc) : "a0");
  asm volatile ("jr a0");
  __builtin_unreachable();
}
