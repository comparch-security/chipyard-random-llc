// See LICENSE for license details.

#include "uart.h"
#include "kprintf.h"
#include "platform.h"
#include "evset.h"
#include "atdect.h"

cacheBlock_t*    target;
cacheBlock_t*    probe;
uint16_t         fails;
uint32_t         accesses;
uint32_t         i;
uint8_t          retrys;
uint16_t         tests;
uint32_t         evsize;
uint8_t          configs;

int main() {
  uart_init();
  kprintln("evset_ppp  in lru cache TESTS %d", TESTS);
  kprintln("config fails aver_accesses");


  for(configs = 0; configs < 9; configs++) {
    //regeneate hkey
    atdect_config0(1, 1, 1, 1);
    probe = (cacheBlock_t*)TEST_START + (2*BLKS);
    for(i = 0; i < 10*BLKS; i++) access((void *)probe++);

    if(configs == 0) { enath = 0;  eneth = 0; enzth   = 0; ath =        0; eth =    10*BLKS; zth = 5; period = 16*4096; }
    if(configs == 1) {          ;           ; enzth   = 1;               ;                 ;        ;                 ; }
    if(configs == 2) {          ;           ;            ;               ;                 ;        ; period =  8*4096; }
    if(configs == 3) {          ;           ;            ;               ;                 ;        ; period =  4*4096; }
    if(configs == 4) {          ;           ;            ;               ;                 ;        ; period =  2*4096; }
    if(configs == 5) {          ;           ;            ;               ;                 ;        ; period =  1*4096; }
    if(configs == 6) {          ;           ;            ;               ;                 ; zth = 4;                 ; }
    if(configs == 7) {          ;           ;            ;               ;                 ; zth = 6;                 ; }
    if(configs == 8) {          ;           ;            ;               ;                 ; zth = 7;                 ; }
    if(configs == 9) { enath = 0;           ; enzth   = 0;               ;                 ;        ;                 ; }
    atdect_config0(ath,  enath, eth, eneth);
    atdect_config1(  5, period, zth, enzth);
    probe = (cacheBlock_t*)TEST_START + (100*BLKS);
    for(i = 0; i < 10*BLKS; i++) access((void *)probe++);

    fails       = 0;
    accesses    = 0;
    for(tests = 0; tests < TESTS; tests++) {
      evsize      = 0;
      target      = (cacheBlock_t*)TEST_TARGET;// + tests;
      for(retrys = 0, evsize = 0; retrys < 1 && evsize < WAYS; retrys++) {
        //step 1 prime
        probe        = (cacheBlock_t*)TEST_START + (4 * retrys * BLKS);
        access((void *)target);
        probe->start = probe;
        probe->prev  = 0;
        for(;;) {
          probe->next = probe + 1;
          if(probe->prev != 0) probe->start = probe->prev->start;
          if(!clcheck_f((void *)target)) break;
          probe++;
          probe->prev = probe - 1;
          accesses++;
        }
        for(probe->next = 0, probe = probe->start; probe->next != 0; probe = probe->next, accesses++);

        //step 2 prune   
        for(probe = probe->start; ; probe = probe->next) {
          if(clcheck_f((void *)probe)) {probe->hit = 1;}
          else                         {probe->hit = 0;}
          if(probe->prev != 0) {
            probe->start = probe->prev->start;
            if(probe->prev->prev != 0 && probe->prev->hit == 0) probe->prev = probe->prev->prev;
            if(probe->hit == 0)                                 probe->prev->next = probe->next;        
          }
          if(probe->start == probe && probe->hit == 0) { probe->start = probe->next; }
          if(probe->next == 0) break;
          accesses++;
        }

        //step 3 probe
        if((uint64_t)probe->start != 0) {
          for(probe = probe->start, access((void *)target); ; probe = probe->next) {
            if(!clcheck_f((void *)probe)) evset[evsize++] = probe;
            access((void *)probe);
            accesses++;
            if(evsize == WAYS || probe->next == 0) break;
          }
        }
      }
      //check
      if(evsize <  WAYS)          fails ++;
      else if(evset_test((void *)target, 1) < 1)  fails ++;

      for(i = 0; i < 10*BLKS; i++);
    }
    kprintln("   %d      %d      %d", configs, fails, accesses/TESTS);
  }
  while(1);
}

