// See LICENSE for license details.

#include <stdint.h>
#include "kprintf.h"
#include "platform.h"

long handle_trap(long cause, long epc, long regs[32])
{
  int i;
  for(i=0;i<31;i++) { kprintln("regs[%lx]=%lx", i, regs[i]);}
  kprintln("mcause=%lx", cause);
  kprintln("mepc=  %lx", epc);
  kprintln("einsn= %lx", *(int*)epc);
  kprintln("sp=    %lx", regs[2]);
  kprintln("tp=    %lx", regs[4]);
  kprintln("while(1) loop()\n");
  while(1);
}
