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
uint16_t         shrinks  ;
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

  for(configs = 0; configs < 4; configs++) {
    //regeneate hkey
    atdect_config0(1, 1, 1, 1);
    fill[0] = (cacheBlock_t*)TEST_START + (2*BLKS);
    for(i = 0; i < 2*BLKS; i++) { access((void*)fill[0]++); }//kprintln("i %x %d", probe, i);}

    if(configs == 0) { enath = 0;  eneth = 0; enzth   = 0; ath =        0; eth =     0*BLKS; zth = 5; period =   4096; }
    if(configs == 1) {          ;           ;            ;               ; eth =    10*BLKS;        ;                ; }
    if(configs == 2) {          ;  eneth = 0; enzth   = 1;               ;                 ;        ;                ; }
    if(configs == 3) {          ;  eneth = 1; enzth   = 1;               ; eth =    10*BLKS;        ;                ; }
    atdect_config0(ath,  enath, eth, eneth);
    atdect_config1(  5, period, zth, enzth);
    for(i = 0; i < 10*BLKS; i++);

    fails    = 0;
    accesses = 0;
    for(tests = 0; tests < TESTS; tests++) {
      //drain out
      target  = (cacheBlock_t*)TEST_TARGET;
      fill[0] = target + 1;
      for(clflushl1_f((void *)target), access((void *)target); clcheck_f((void *)target); access((void*)fill[0]++), accesses++);

      //step 1 initialize candidates
      fill[0]      = (cacheBlock_t*)TEST_START ;
      clflushl1_f((void *)target);
      for(i = 0; i < 1; i++) {
        accessWithfence((void *)target);
        fill[i]->start = fill[i];
        fill[i]->prev  = 0;
        for(evsize[i] = 1; clcheck_f((void *)target); ) {
          fill[i]->next = fill[i] + 1;
          if(fill[i]->prev != 0) { fill[i]->start = fill[i]->prev->start; }
          fill[i]++;
          fill[i]->prev = fill[i] - 1;
          accesses++;
          if(evsize[i]++ == 2) { clflushl1_f((void *)target); access((void *)target); }
        }
        fill[i]->next        = fill[i]->prev->start;
        fill[i]->start->prev = fill[i];
        fill[i]              = fill[i]->prev->prev;
        if(i==0) { evsize[1] = evsize[0] + 100; fill[1] = (cacheBlock_t*)(0xa0000000); }
      }
      
      //kprintln("%d, %x, %x, %x, %x, %x",evsize[0], fill[0], fill[0]->next, fill[0]->prev, fill[0]->start, fill[0]->prev->start);
      //kprintln("%d, %x, %x, %x, %x, %x",evsize[1], fill[1], fill[1]->next, fill[1]->prev, fill[1]->start, fill[1]->prev->start);

      loops     = 0;
      shrinks   = 0;
      candidates[0] = evsize[0]*split + 1;
      candidates[1] = evsize[1]*split + 1;
      for(i = 0; evsize[i] > 16; i = (i+1) & 00) {
        for(fill[1] = target + 1; clcheck_f((void *)target); access((void*)fill[1]++), accesses++);
        accessWithfence((void *)target);
        fill[i]->start = fill[i];
        //step 2 fill
        for(j = 1; j < candidates[i]; j++) {
          fill[i]->next->prev = fill[i];
          fill[i]             = fill[i]->next;
          fill[i]->start      = fill[i]->prev->start;
          accesses++;
        }
        //step 3 check and shrink
        if(!clcheck_f((void *)target)) {
          //access((void *)target);
          shrinks++;
          evsize[i]      = candidates[i];
          candidates[i]  = evsize[i]*split ;
          if(candidates[i] < 100) candidates[i] ++;
          if(candidates[i] >= evsize[i]) candidates[i] = evsize[i] - 1;
          //kprintln("i %d %d  %d", i, candidates[i], evsize[i]);
          fill[i]->next        = fill[i]->start;
          fill[i]->next->prev  = fill[i];
          fill[i]              = fill[i]->next;
        } else {
          fill[i]->start       = fill[i]->next;
          fill[i]              = fill[i]->start;
        }
        if(loops++ > 1000 || (loops > 100 && (loops > (shrinks << 4)))) break;
      }

      i = 0;
      if(evsize[1] == WAYS)          i = 1;
      else if(evsize[1] < evsize[0]) i = 1;
      clflushl1_f((void *)target); accessWithfence((void *)target);
      for(j = 0, fill[0] = fill[0]->start, fill[1] = fill[1]->start; j < evsize[i]; fill[i] = fill[i]->next, j++);
      if(clcheck_f((void *)target) || evsize[i] > SETS) fails++;
      accessWithfence((void *)target);
      //kprintln("tests %d %d %d %d %d", tests, fails, evsize[i], loops ,shrinks);
    }
    kprintln("   %d      %d      %d", configs, fails, accesses/TESTS);
  }
  while(1);
}