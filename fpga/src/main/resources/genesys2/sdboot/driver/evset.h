#include "platform.h"

#define CFCMD_ADDR          L2_CTRL_ADDR + L2_FLUSH64
#define CSTATE_ADDR         L2_CTRL_ADDR + L2_BLKSTATE

#define TEST_TARGET         0x80080000
#define TEST_START          0xb0000000
#define TEST_END            0xc0000000
#define TESTS               10
#define SETS                1024
#define WAYS                16
#define BLKS                (SETS*WAYS)

#define THRL2MISS           60

void      clflush_f(void *p);
void      clflushl1_f(void *p);
uint8_t   clcheck_f(void *p);
uint8_t   accl2hit(void *p)__attribute__((noinline));
uint16_t  evset_test(void *target, uint8_t tests);


typedef struct cacheBlock {
  uint8_t               hit;
  uint8_t               pad[7];
  struct cacheBlock*    start;
  struct cacheBlock*    prev;
  struct cacheBlock*    next;
  uint64_t              line[4];
}__attribute__((aligned(64))) cacheBlock_t;


cacheBlock_t*    evset[WAYS];

inline void access(void *p)
{ 
  //asm volatile ("mv zero, %0" :: "r"(*(uint8_t*)p));
  asm volatile ("" :: "r"(*(uint8_t*)p));
}

inline void accessWithfence(void *p)
{ 
  asm volatile ("fence");
  //asm volatile ("mv zero, %0" :: "r"(*(uint8_t*)p));
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
    : "a0");                    // clobber registers

  return time;
}

inline void clflush_f(void *p)
{ 
  asm volatile ("fence");
  *(volatile uint64_t *)(CFCMD_ADDR) = ((uint64_t)p << L2_CMDBITS) + 1;
  asm volatile ("fence");
}

inline void clflushl1_f(void *p)
{ 
  asm volatile ("fence");
  *(volatile uint64_t *)(CFCMD_ADDR) = ((uint64_t)p << L2_CMDBITS) + 2;
  asm volatile ("fence");
}

inline uint8_t clcheck_f(void *p)
{ 
  asm volatile ("fence");
  *(volatile uint64_t *)(CFCMD_ADDR) = ((uint64_t)p << L2_CMDBITS) + 3;
  asm volatile ("fence");
  return (*(volatile uint64_t *)(CSTATE_ADDR));
}

uint8_t accl2hit(void *p)
{
  //return clcheck_f(p);
  if(timeAccess(p) < THRL2MISS) return 1;
  return 0;
};


uint16_t evset_test(void *target, uint8_t tests) {
  uint8_t  i;
  uint16_t j;
  uint16_t passes = 0;
  for(i = 0; i < tests || (i == 0 && tests == 0); i++) {
    access((void *)target);
    for(j = 0; j<WAYS; j++) { accessWithfence((void *)evset[j]); }
    if(clcheck_f((void *)target) == 0) passes++;
  }
  return passes;
}






