#ifndef SCE_ASSEMBLY_HPP
#define SCE_ASSEMBLY_HPP
//#define POSTERN

#include <cstdint>
#include "platform.h"

inline void flush(void *p) {
  *(volatile uint64_t *)(L2_CTRL_ADDR + L2_FLUSH64) = ((uint64_t)p << L2_CMDBITS) + 1;
}

inline void flushl1(void *p)
{ 
  *(volatile uint64_t *)(L2_CTRL_ADDR + L2_FLUSH64) = ((uint64_t)p << L2_CMDBITS) + 2;
}

inline uint8_t clcheck_f(void *p)
{ 
  *(volatile uint64_t *)(L2_CTRL_ADDR + L2_FLUSH64) = ((uint64_t)p << L2_CMDBITS) + 3;
  return (*(volatile uint64_t *)(L2_CTRL_ADDR + L2_BLKSTATE));
}

inline uint64_t rdtscfence() {
  uint64_t time;

  __asm__ volatile (
    "fence                 \n"
    "rdcycle %0            \n"
    "fence                 \n"
    : "=r"(time)                // output
    :                           // input
    :);                         // clobber registers

  return time;
}

inline void maccess(void* p) {
  __asm__ volatile ("" :: "r"(*(uint8_t*)p));
}

inline uint64_t maccess_time(void* p) {
  uint64_t delay = 80;
  if(clcheck_f(p)) delay = 40;
  __asm__ volatile ("" :: "r"(*(uint8_t*)p));
  return delay;
}

inline void maccess_fence(void* p) {
  __asm__ volatile ("fence");
  __asm__ volatile ("" :: "r"(*(uint8_t*)p));
  __asm__ volatile ("fence");
}

#endif
