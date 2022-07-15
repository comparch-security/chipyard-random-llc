// See LICENSE for license details.

#include "uart.h"
#include "kprintf.h"
#include "platform.h"
#include "evset.h"
#include "atdect.h"

#define split 16/17

cacheBlock_t*    target;
cacheBlock_t*    fill[2];
uint16_t         loops    ;
uint8_t          shrinked ;
uint16_t         shrinks  ;
uint8_t          failed   ;
uint16_t         fails    ;
uint32_t         accesses ;
uint32_t         i;
uint32_t         j;
uint16_t         tests;
uint8_t          configs;
uint32_t         evsize[2];
uint32_t         candidates[2];

int main() {
  uart_init();
  kprintln("evset_ge in lru cache TESTS %d", TESTS);
  kprintln("config fails aver_accesses");

  for(configs = 0; ; configs = 0) {
    for( ; configs < 4; configs++) {
      //regeneate hkey
      atdect_config0(1, 1, 1, 1);
      for(i = 0, fill[1] = (cacheBlock_t*)TEST_START + (10*BLKS); i < 2*BLKS; access((void*)fill[1]++), i++);

      if(configs == 0) { enath = 0;  eneth = 0; enzth   = 0; ath =        0; eth =    10*BLKS; zth = 5; period =   4096; }
      if(configs == 1) {          ;           ; enzth   = 1;               ;                 ;        ;                ; }
      if(configs == 2) {          ;  eneth = 1; enzth   = 0;               ;                 ;        ;                ; }
      if(configs == 3) {          ;           ; enzth   = 1;               ;                 ;        ;                ; }
      atdect_config0(ath,  enath, eth, eneth);
      atdect_config1(  5, period, zth, enzth);
      for(i = 0; i < 10*BLKS; i++);

      failed   = 1;
      fails    = 0;
      accesses = 0;
      for(tests = 0; tests < TESTS; tests++) {
        //drain out
        target  = (cacheBlock_t*)TEST_TARGET - (tests & 0xff);
        if(failed) for(i = 0, fill[1] = target + 0x1ff; i < 2*BLKS; access((void*)fill[1]++), i++);

        //step 1 initialize candidates
        fill[0] = (cacheBlock_t*)TEST_START;
        for(i = 0; i < 1; i++) {
          accessWithfence((void *)target);
          fill[i]->prev  = 0;
          fill[i]->next  = fill[i] + 1;
          fill[i]->start = fill[i];
          fill[i]++;
          fill[i]->prev  = fill[i] - 1;
          for(evsize[i] = 2; accl2hit((void *)target); ) {
            fill[i]->next = fill[i] + 1;
            fill[i]->start = fill[i]->prev->start;
            fill[i]++;
            fill[i]->prev = fill[i] - 1;
            accesses++;
            if(evsize[i]++ == 3) {
              for(fill[1] = target + 1, access((void *)target); accl2hit((void *)target); access((void*)fill[1]++), accesses++);
              access((void *)target);
            }
          }
          accessWithfence((void *)target);
          fill[i]->start       = fill[i]->prev->start;
          fill[i]->next        = fill[i]->start;
          fill[i]->start->prev = fill[i];
          fill[i]              = fill[i]->prev->prev;
          if(i==0) { evsize[1] = evsize[0] + 100; fill[1] = (cacheBlock_t*)(0xa0000000); }
        }

        //kprintln("%d, %x, %x, %x, %x, %x",evsize[0], fill[0], fill[0]->next, fill[0]->prev, fill[0]->start, fill[0]->prev->start);
        //kprintln("%d, %x, %x, %x, %x, %x",evsize[1], fill[1], fill[1]->next, fill[1]->prev, fill[1]->start, fill[1]->prev->start);

        loops     = 0;
        shrinked  = 0;
        shrinks   = 0;
        candidates[0] = evsize[0]*split + 1;
        candidates[1] = evsize[1]*split + 1;
        for(i = 0; evsize[i] > 16; i = (i+1) & 00) {
          if(shrinked == 0) {
            accessWithfence((void *)target);
            for(fill[1] = target + 1; accl2hit((void *)target); access((void*)fill[1]++), accesses++);
            accessWithfence((void *)target);
          }
          fill[i]->start = fill[i];
          //step 2 fill
          for(j = 1; j < candidates[i]; j++) {
            fill[i]->next->prev = fill[i];
            fill[i]             = fill[i]->next;
            fill[i]->start      = fill[i]->prev->start;
            accesses++;
          }
          shrinked = 0;
          //step 3 check and shrink
          if(!accl2hit((void *)target)) {
            accessWithfence((void *)target);
            shrinks++;
            shrinked       = 1;
            evsize[i]      = candidates[i];
            candidates[i]  = evsize[i]*split ;
            if(candidates[i] < 100) candidates[i] ++;
            if(candidates[i] >= evsize[i]) candidates[i] = evsize[i] - 1;
            //kprintln("i %d %d  %d", i, candidates[i], evsize[i]);
            fill[i]->next        = fill[i]->start;
            fill[i]->next->prev  = fill[i];
            fill[i]              = fill[i]->next;
          } else {
            accessWithfence((void *)target);
            fill[i]->start       = fill[i]->next;
            fill[i]              = fill[i]->start;
          }
          if(loops++ > 1000 || (loops > 100 && (loops > (shrinks << 4)))) break;
        }

        failed = 0;
        i = 0;
        //if(evsize[1] == WAYS)          i = 1;
        //else if(evsize[1] < evsize[0]) i = 1;
        access((void *)target);
        for(fill[1] = target + 1; accl2hit((void *)target); access((void*)fill[1]++), accesses++);
        access((void *)target);
        for(j = 0, fill[0] = fill[0]->start, fill[1] = fill[1]->start; j < evsize[i]; fill[i] = fill[i]->next, j++);
        if(accl2hit((void *)target) || evsize[i] > SETS) { failed = 1; fails++; }
        access((void *)target);
        if(!failed) {
          for(j = 0, fill[0] = fill[0]->start; j < (evsize[i] << 1); fill[i] = fill[i]->next, j++);
        }
        //kprintln("tests %d %d %d %d %d", tests, fails, evsize[i], loops ,shrinks);
        //while(1);
      }
      kprintln("   %d      %d      %d", configs, fails, accesses/TESTS);
    }
  }
}
