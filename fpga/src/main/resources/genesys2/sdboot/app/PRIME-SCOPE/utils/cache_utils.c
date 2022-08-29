/*
  These cache utils have been stitched together over time, including modifications.
  We try to attribute them to original sources where we can.
*/


#include <stdint.h>
#include "cache_utils.h"
#include "platform.h"


inline void clflush(void *p)
{
	*(volatile uint64_t *)(L2_CTRL_ADDR + L2_FLUSH64) = ((uint64_t)p << L2_CMDBITS) + 1;
}

inline void clflush_f(void *p)
{
  __asm__ volatile ("fence");
  *(volatile uint64_t *)(L2_CTRL_ADDR + L2_FLUSH64) = ((uint64_t)p << L2_CMDBITS) + 1;
  __asm__ volatile ("fence");
}

inline void clflushl1(void *p)
{ 
  *(volatile uint64_t *)(L2_CTRL_ADDR + L2_FLUSH64) = ((uint64_t)p << L2_CMDBITS) + 2;
}

inline uint8_t clcheck(void *p)
{ 
  *(volatile uint64_t *)(L2_CTRL_ADDR + L2_FLUSH64) = ((uint64_t)p << L2_CMDBITS) + 3;
  return (*(volatile uint64_t *)(L2_CTRL_ADDR + L2_BLKSTATE));
}

inline uint64_t rdcyclefence()
{
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

inline uint64_t rdcycle()
{
  uint64_t time;

  __asm__ volatile (
    "rdcycle %0            \n"
    : "=r"(time)                // output
    :                           // input
    :);                         // clobber registers

  return time;
}

inline uint8_t maccess_check(void* p, void* phyadr)
{
  uint8_t hit=0;
  if(clcheck(phyadr)) hit = 1;
  __asm__ volatile ("" :: "r"(*(uint8_t*)p));
  return hit;
}

inline void maccess_fence(void* p)
{
  __asm__ volatile ("fence");
  __asm__ volatile ("" :: "r"(*(uint8_t*)p));
  __asm__ volatile ("fence");
}

inline void maccess(void* p)
{
  __asm__ volatile ("" :: "r"(*(uint8_t*)p));
}

inline void mwrite(void *v)
{
  asm volatile (
    "fence                 \n"
    "sb zero, 0(%0)        \n"
    "fence                 \n"
    :                         // output
    : "r" (v)                 // input
    : );                      // clobber registers
}

inline int mread(void *v)
{
  int rv;
  asm volatile (
    "lb %0, 0(%1)          \n"
    : "=r"(rv)               // output
    : "r"(v)                 // input
    : );                     // clobber registers
  return rv;
}

inline int time_mread(void *adrs) 
{
  int delay;
  asm volatile (
    "fence                 \n"
    "rdcycle t1            \n"
    "lb %0, 0(%1)          \n"
    "rdcycle %0            \n"
    "sub %0, %0, t1        \n"
    : "=r"(delay)               // output
    : "r"(adrs)                 // input
    : "t1");                    // clobber registers
  return delay;
}

inline int time_mread_nofence(void *adrs)
{
  int delay;
  asm volatile (
    "rdcycle t1            \n"
    "lb %0, 0(%1)          \n"
    "rdcycle %0            \n"
    "sub %0, %0, t1        \n"
    : "=r"(delay)               // output
    : "r"(adrs)                 // input
    : "t1");                    // clobber registers
  return delay;
}

