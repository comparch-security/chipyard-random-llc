// See LICENSE for license details.

#include "uart.h"
#include "kprintf.h"
#include "platform.h"
#include "evset.h"
#include "atdect.h"

cacheBlock_t*    target;
cacheBlock_t*    probe;
uint8_t          failed;
uint16_t         fails[2];
uint32_t         accesses[2];
uint32_t         i;
uint8_t          j;
uint8_t          retrys;
uint16_t         tests;
uint32_t         evsize;
uint8_t          configs;
uint8_t          speed;

int main() {
  uart_init();
  kprintln("evset_ppp  in lru cache TESTS %d", TESTS);
  kprintln("config slow fails aver_accesses fast fails aver_accesses");


  for(configs = 0; ; configs = 0) {
    for( ; configs < 9; configs++) {
      //regeneate hkey
      atdect_config0(1, 1, 1, 1);
      probe = (cacheBlock_t*)TEST_START + (2*BLKS);
      for(i = 0; i < 2*BLKS; i++) access((void *)probe++);

      if(configs == 0) { enath = 0;  eneth = 0; enzth   = 0; ath =        0; eth =    10*BLKS; zth = 5; period = 16*4096; }
      if(configs == 1) {          ;           ; enzth   = 1;               ;                 ;        ;                 ; }
      if(configs == 2) {          ;           ;            ;               ;                 ;        ; period =  8*4096; }
      if(configs == 3) {          ;           ;            ;               ;                 ;        ; period =  4*4096; }
      if(configs == 4) {          ;           ;            ;               ;                 ;        ; period =  2*4096; }
      if(configs == 5) {          ;           ;            ;               ;                 ;        ; period =  1*4096; }
      if(configs == 6) {          ;           ;            ;               ;                 ; zth = 4;                 ; }
      if(configs == 7) {          ;           ;            ;               ;                 ; zth = 6;                 ; }
      if(configs == 8) {          ;           ;            ;               ;                 ; zth = 7;                 ; }
      atdect_config0(ath,  enath, eth, eneth);
      atdect_config1(  5, period, zth, enzth);
      probe = (cacheBlock_t*)TEST_START + (100*BLKS);
      for(i = 0; i < 10*BLKS; i++) access((void *)probe++);

      failed      = 1;
      fails[0]    = 0; fails[1]    = 0;
      accesses[0] = 0; accesses[1] = 0;
      for(tests = 0,  speed = 0; tests < 2*TESTS; tests++) {
        speed = (tests + 1) & 0x01;
        evsize      = 0;
        target      = (cacheBlock_t*)TEST_TARGET - ((tests >> 1) & 0xff);
        for(retrys = 0, evsize = 0; retrys < 1 && evsize < WAYS; retrys++) {
          //drain out
          for(i = 0, probe = target + 0x1ff; i < 2*BLKS; access((void *)probe++), i++);

          //step 1 prime
          accessWithfence((void *)target);
          probe        = (cacheBlock_t*)TEST_START + ((uint32_t)tests & 0x03) * 4 * BLKS;
          probe->prev  = 0;
          probe->next  = probe + 1;
          probe->start = probe;
          probe++;
          probe->prev  = probe - 1;
          for(;;) {
            probe->next  = probe + 1;
            probe->start = probe->prev->start;
            if(!accl2hit((void *)target)) break;
            probe++;
            probe->prev = probe - 1;
            accesses[speed]++;
          }
          accessWithfence((void *)target);
          probe->next = 0;
          probe       = probe->start;
          if(speed == 0) {
            for( ; ; probe = probe->next, accesses[speed]++) {
              //asm volatile ("fence");
              if(probe->next == 0) { probe = probe->start; break; }
            }
          }

          //step 2 prune
          for( ; ; probe = probe->next) {
            if(accl2hit((void *)probe)) {probe->hit = 1;}
            else                        {probe->hit = 0;}
            if(probe->prev != 0) {
              probe->start = probe->prev->start;
              if(speed == 0) {
                if(probe->prev->prev != 0 && probe->prev->hit == 0) probe->prev = probe->prev->prev;
                if(probe->hit == 0)                                 probe->prev->next = probe->next;
              } else {
                if(probe->prev->prev != 0 && probe->prev->hit == 1) probe->prev = probe->prev->prev;
                if(probe->hit == 1)                                 probe->prev->next = probe->next;
              }
            }
            if(speed == 0) { if(probe->start == probe && probe->hit == 0) { probe->start = probe->next; }}
            else           { if(probe->start == probe && probe->hit == 1) { probe->start = probe->next; }}
            if(probe->next == 0) break;
            accesses[speed]++;
          }
          if(probe != 0) {
            if(probe->start != 0) {
              for(probe = probe->start; ; probe = probe->next, accesses[speed]++) {
                asm volatile ("fence");
                if(probe->next == 0) break;
              }
            }
          }
          asm volatile ("fence");
          //if(accl2hit((void *)target)) {kprintln("hit");} else {kprintln("miss");}

          //step 3 probe
          if(probe != 0) {
            if(probe->start != 0) {
              if(speed == 0) accessWithfence((void *)target);
              for(probe = probe->start; ; probe = probe->next) {
                if(speed == 0) { if(!accl2hit((void *)probe)) evset[evsize++] = probe; }
                else           { if( accl2hit((void *)probe)) evset[evsize++] = probe; }
                access((void *)probe);
                accesses[speed]++;
                if(evsize == WAYS || probe->next == 0) break;
              }
            }
          }
        }

        //check
        //for(i = 0, probe = target + 1; i < 2*BLKS; access((void *)probe++), i++);
        failed = 0;
        if(evsize <  WAYS)                          { failed = 1; fails[speed] ++; }
        else if(evset_test((void *)target, 2) < 2)  { failed = 2; fails[speed] ++; }
        //if(failed && speed == 1) { kprintln("faile %d", failed);   while(1); }
        if(!failed) for(i = 0; i < WAYS; access(evset[i++]));
        for(i = 0; i < 10*BLKS; i++);
      }

      accesses[0] = accesses[0]/TESTS;
      accesses[1] = accesses[1]/TESTS;
      kprintln("   %d    %d    %d    %d    %d", configs, fails[0], accesses[0], fails[1], accesses[1]);
    }
  }
}

