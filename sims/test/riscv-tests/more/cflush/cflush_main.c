#include <assert.h>
#include <stdlib.h>
#include <stdio.h>
#include "util.h"

#define L2_CTRL_ADDR        0x2010000
#define L2_FLUSH64          0x200
#define L2_BLKSTATE         0x250
#define L2_CMDBITS          8
#define CFCMD_ADDR          L2_CTRL_ADDR + L2_FLUSH64
#define CSTATE_ADDR         L2_CTRL_ADDR + L2_BLKSTATE

void     clflush_f(void *p);
void     clflushl1_f(void *p);
uint8_t  clcheck_f(void *p);

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


int main (int argc, char** argv) {
  volatile uint64_t  data;
  volatile uint64_t* p = (uint64_t*)0x82000000;

  printf("1\n");
  printf("state : %d\n", clcheck_f((void *)p));
  printf("state : %d\n", clcheck_f((void *)p));

  data = *p;
  *p   = data;
  printf("2\n");
  printf("state : %d\n", clcheck_f((void *)p));
  printf("state : %d\n", clcheck_f((void *)p));

  data = *p;
  *p   = data;
  clflush_f((void *)p);
  printf("3\n");
  printf("state : %d\n", clcheck_f((void *)p));
  printf("state : %d\n", clcheck_f((void *)p));
  
  data = *p;
  *p   = data;
  printf("4\n");
  printf("state : %d\n", clcheck_f((void *)p));
  printf("state : %d\n", clcheck_f((void *)p));

  data = *p;
  *p   = 1209;
  clflushl1_f((void *)p);
  printf("5\n");
  printf("state : %d\n", clcheck_f((void *)p));
  if(*p != 1209) { printf("error\n"); }

  return 0;
}
