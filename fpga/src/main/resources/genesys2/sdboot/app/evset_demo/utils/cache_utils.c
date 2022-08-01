/*
  These cache utils have been stitched together over time, including modifications.
  We try to attribute them to original sources where we can.
*/


#include <stdint.h>
#include "cache_utils.h"
#include "platform.h"

inline void clflush_f(void *p)
{ 
  asm volatile ("fence");
  *(volatile uint64_t *)(L2_CTRL_ADDR + L2_FLUSH64) = ((uint64_t)p << L2_CMDBITS) + 1;
  asm volatile ("fence");
}

inline void clflushl1_f(void *p)
{ 
  asm volatile ("fence");
  *(volatile uint64_t *)(L2_CTRL_ADDR + L2_FLUSH64) = ((uint64_t)p << L2_CMDBITS) + 2;
  asm volatile ("fence");
}

inline uint8_t clcheck_f(void *p)
{ 
  asm volatile ("fence");
  *(volatile uint64_t *)(L2_CTRL_ADDR + L2_FLUSH64) = ((uint64_t)p << L2_CMDBITS) + 3;
  asm volatile ("fence");
  return (*(volatile uint64_t *)(L2_CTRL_ADDR + L2_BLKSTATE));
}

inline void accessNofence(void *p)
{ 
  //asm volatile ("mv zero, %0" :: "r"(*(uint8_t*)p));
  asm volatile ("" :: "r"(*(uint8_t*)p));
}

inline void accessWithfence(void *p)
{ 
  asm volatile ("fence");
  asm volatile ("" :: "r"(*(uint8_t*)p));
  asm volatile ("fence");
}

inline uint64_t timeAccess(void *p) 
{
  uint64_t time;

  asm volatile (
    "rdcycle t1            \n"
    "lb %0, 0(%1)          \n"
    "rdcycle %0            \n"
    "sub %0, %0, t1        \n"
    : "=r"(time)                // output
    : "r"(p)                    // input
    : "t1");                    // clobber registers

  return time;
}

uint64_t timeAccessNoinline(void *p) 
{
  uint64_t time;

  asm volatile (
    "rdcycle t1            \n"
    "lb %0, 0(%1)          \n"
    "rdcycle %0            \n"
    "sub %0, %0, t1        \n"
    : "=r"(time)                // output
    : "r"(p)                    // input
    : "t1");                    // clobber registers

  return time;
}

uint8_t accl2hit(void *p)
{
  if(timeAccess(p) < THRL2MISS) return 1;
  return 0;
};

uint8_t accl2miss(void *p)
{
  uint64_t time = timeAccess(p);
  if(55 < time && time < 90) return 1;
  return 0;
};

uint16_t evset_test(void *target, uint8_t tests) {
  uint8_t  i;
  uint16_t j;
  uint16_t passes = 0;
  for(i = 0; i < tests || (i == 0 && tests == 0); i++) {
    accessNofence((void *)target);
    for(j = 0; j<EVSIZE;    j++) { accessWithfence((void *)evset[j]); }
    for(j = 0; j<THRL2MISS; j++);
    if(accl2hit((void *)target) == 0) passes++;
  }
  return passes;
}