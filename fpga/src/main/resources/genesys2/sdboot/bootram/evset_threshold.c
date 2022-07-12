// See LICENSE for license details.

#include "uart.h"
#include "kprintf.h"
#include "platform.h"
#include "evset.h"
#include "atdect.h"


cacheBlock_t*    probe;
cacheBlock_t*    target;
uint8_t    evsize;
uint32_t   i;
uint32_t   j;
uint64_t   time[2];

int main() {
  uart_init();
  kprintln("evset_threshold in lru cache TESTS %d", TESTS);
  atdect_config0(0, 0, 0, 0);
  atdect_config1(0, 0, 0, 0);
  target      = (cacheBlock_t*)TEST_TARGET;
  probe       = (cacheBlock_t*)TEST_TARGET;

  while(1) {
    for(evsize = 0; evsize < WAYS; ) {
      for(j = 0; j<evsize; j++) access((void *)(evset[j]));
      for(; clcheck_f((void *)target); access((void *)probe++));
      accessWithfence((void *)target);
      evset[evsize++] = probe - 1;
    }
    if(evset_test((void *)target, 2) == 2) break;
  }

  kprintln("L1_HIT CYCLES");
  timeAccess(target);
  time[0] = 0; time[1] = 0;
  for(i = 0; i < TESTS; i++) {
    time[0] = timeAccess(target);
    time[1] = time[1] + time[0];
    if(i < 16) kprintf(" %d ", time[0]);
  }
  kprintln(" %d \n", time[1]);

  kprintln("L2_HIT CYCLES");
  time[0] = 0; time[1] = 0;
  for(i = 0; i < TESTS; i++) {
    clflushl1_f(target);
    time[0] = timeAccess(target);
    time[1] = time[1] + time[0];
    if(i < 16) kprintf(" %d ", time[0]);
  }
  kprintln(" %d \n", time[1]);

  kprintln("L2_MISS CYCLES");
  time[0] = 0; time[1] = 0;
  for(i = 0; i < TESTS; i++) {
    clflush_f(target);
    time[0] = timeAccess(target);
    time[1] = time[1] + time[0];
    if(i < 16) kprintf(" %d ", time[0]);
  }
  kprintln(" %d \n", time[1]);


  kprintln("L2_MISS AND EVICT L2 CLEAN BLOCK CYCLES");
  evset_test((void *)target, 2);
  time[0] = 0; time[1] = 0;
  for(i = 0; i < TESTS; i++) {
    for(j = 0; j < WAYS; j++) {
      access(evset[j]);
      clflushl1_f(evset[j]);
    }
    time[0] = timeAccess(target);
    time[1] = time[1] + time[0];
    if(i < 16) kprintf(" %d ", time[0]);
  }
  kprintln(" %d \n", time[1]);

  kprintln("L2_MISS AND EVICT L2 DIRTY BLOCK CYCLES");
  evset_test((void *)target, 2);
  time[0] = 0; time[1] = 0;
  for(i = 0; i < TESTS; i++) {
    for(j = 0; j < WAYS; j++) {
      evset[j]->line[0] = 0;
      clflushl1_f(evset[j]);
    }
    time[0] = timeAccess(target);
    time[1] = time[1] + time[0];
    if(i < 16) kprintf(" %d ", time[0]);
  }
  kprintln(" %d \n", time[1]);

  kprintln("L2_MISS AND EVICT L1 L2 CLEAN BLOCK CYCLES");
  evset_test((void *)target, 2);
  time[0] = 0; time[1] = 0;
  for(i = 0; i < TESTS; i++) {
    for(j = 0; j < WAYS; j++) {
      accessWithfence(evset[j]);
    }
    time[0] = timeAccess(target);
    time[1] = time[1] + time[0];
    if(i < 16) kprintf(" %d ", time[0]);
  }
  kprintln(" %d \n", time[1]);

  kprintln("L2_MISS AND EVICT L1 L2 DIRTY BLOCK CYCLES");
  evset_test((void *)target, 2);
  time[0] = 0; time[1] = 0;
  for(i = 0; i < TESTS; i++) {
    for(j = 0; j < WAYS; j++) {
      accessWithfence(evset[j]);
      evset[j]->line[0] = 0;
    }
    time[0] = timeAccess(target);
    time[1] = time[1] + time[0];
    if(i < 16) kprintf(" %d ", time[0]);
  }
  kprintln(" %d \n", time[1]);

  
  while(1);
}
