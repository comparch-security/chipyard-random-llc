// See LICENSE for license details.

#include "uart.h"
#include "kprintf.h"
#include "platform.h"
#include "evset.h"
#include "atdect.h"


cacheBlock_t*    target;
cacheBlock_t*    probe;
uint32_t   evsize;
uint8_t    configs;
uint16_t   fails[2] ;
uint8_t    speed;
uint32_t   accesses[2] ;
uint32_t   i;

void prepare(uint8_t speed, uint8_t casize, cacheBlock_t** evtarget) { //fill congruent addresses
    access((void *)(*evtarget));
   if(speed == 1) { for(uint8_t i = 0; i<casize; i++) access((void *)(evset[i])); }
}

int main() {
  uart_init();
  kprintln("evset_ct in lru cache TESTS %d", TESTS);
  kprintln("config  slow fails aver_accesses fast fails aver_accesses");

  speed = 0;

  for(configs = 0; configs < 9; configs++) {
    //regeneate hkey
    atdect_config0(1, 1, 1, 1);
    probe = (cacheBlock_t*)TEST_START;
    for(i = 0; i < 2*BLKS; i++) access((void *)probe++);

    if(configs == 0) { enath = 0;  eneth = 0; enzth   = 0; ath =        0; eth =     0*BLKS; zth = 5; period =   4096; }
    if(configs == 1) {          ;  eneth = 1;            ;               ; eth = 16*10*BLKS;        ;                ; }
    if(configs == 2) {          ;           ;            ;               ; eth =  8*10*BLKS;        ;                ; }
    if(configs == 3) {          ;           ;            ;               ; eth =  4*10*BLKS;        ;                ; }
    if(configs == 4) {          ;           ;            ;               ; eth =  2*10*BLKS;        ;                ; }
    if(configs == 5) {          ;           ;            ;               ; eth =    10*BLKS;        ;                ; }
    if(configs == 6) {          ;           ;            ;               ; eth =     9*BLKS;        ;                ; }
    if(configs == 7) {          ;  eneth = 0; enzth   = 1;               ;                 ;        ;                ; }
    if(configs == 8) {          ;  eneth = 1; enzth   = 1;               ; eth =    10*BLKS;        ;                ; }
    atdect_config0(ath,  enath, eth, eneth);
    atdect_config1(  5, period, zth, enzth);
    probe = (cacheBlock_t*)TEST_START + (100*BLKS);
    for(i = 0; i < 10*BLKS; i++) access((void *)probe++);

    fails[0]    = 0;
    fails[1]    = 0;
    accesses[0] = 0;
    accesses[1] = 0;
    for(uint16_t tests = 0, speed = 0; tests < TESTS * 2; tests++) {
      if(tests >= TESTS) speed = 1;
      target      = (cacheBlock_t*)TEST_TARGET;// + tests;
      probe       = (cacheBlock_t*)TEST_START;
      for(evsize = 0; evsize < WAYS; ) {
        for(prepare(speed, evsize, &target); clcheck_f((void *)target); access((void *)probe++)) accesses[speed] ++;
        evset[evsize++] = probe - 1;
      }
      //check
      if(evset_test((void *)target, 1) < 1) fails[speed] ++;
    }
    //kprintln("config  slow fails aver_accesses fast fails aver_accesses");
    kprintln("   %d           %d      %d           %d      %d ", 
              configs,  fails[0], accesses[0]/TESTS, fails[1], accesses[1]/TESTS);
  }
  while(1);
}
