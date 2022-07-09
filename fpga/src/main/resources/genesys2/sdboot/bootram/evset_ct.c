// See LICENSE for license details.

#include "uart.h"
#include "kprintf.h"
#include "platform.h"
#include "evset.h"
#include "atdect.h"


cacheBlock_t*    target[2];
cacheBlock_t*    probe;
uint32_t   evsize;
uint8_t    configs;
uint16_t   fails[3] ;
uint8_t    speed;
uint32_t   accesses[3] ;
uint32_t   i;
uint32_t   j;

inline void prepare(uint8_t speed, uint8_t evsize, cacheBlock_t** evtarget) { //fill congruent addresses
  if(speed < 2) {
    *(evtarget + 1) = *evtarget;
    access((void *)(*evtarget));
    if(speed == 1) { for(j = 0; j<evsize; j++) access((void *)(evset[j])); }
  } else {
    switch(evsize) {
      case 0x00  : accessWithfence((void *)(*evtarget)); *(evtarget + 1) = *evtarget ; break;  //A 0
      case 0x01  :                                     ; *(evtarget + 1) =  evset[0] ; break;  //B 1
      case 0x02  : accessWithfence((void *)(*evtarget)); *(evtarget + 1) =  evset[1] ; break;  //C 2
      case 0x03  :                                     ; *(evtarget + 1) = *evtarget ; break;  //D 3
      case 0x04  : accessWithfence((void *)(evset[0])) ; *(evtarget + 1) =  evset[2] ; break;  //E 4
      case 0x05  :                                     ; *(evtarget + 1) =  evset[3] ; break;  //F 5
      case 0x06  :                                     ; *(evtarget + 1) =  evset[0] ; break;  //G 6
      case 0x07  : accessWithfence((void *)(evset[1])) ;
                   accessWithfence((void *)(*evtarget)); *(evtarget + 1) =  evset[4] ; break;  //H 7
      case 0x08  :                                     ; *(evtarget + 1) =  evset[5] ; break;  //I 8
      case 0x09  :                                     ; *(evtarget + 1) =  evset[6] ; break;  //J 9
      case 0x0a  :                                     ; *(evtarget + 1) =  evset[1] ; break;  //K 10
      case 0x0b  : accessWithfence((void *)(evset[2])) ;
                   accessWithfence((void *)(evset[3])) ;
                   accessWithfence((void *)(evset[0])) ;
                   accessWithfence((void *)(*evtarget)); *(evtarget + 1) =  evset[7] ; break;  //L 11
      case 0x0c  :                                     ; *(evtarget + 1) =  evset[8] ; break;  //M 12
      case 0x0d  :                                     ; *(evtarget + 1) =  evset[9] ; break;  //N 13
      case 0x0e  :                                     ; *(evtarget + 1) =  evset[10]; break;  //O 14
      case 0x0f  :                                     ; *(evtarget + 1) =  evset[2] ; break;  //P 15
      default    :                                     ; *(evtarget + 1) = *evtarget ; break;
    }
  }
}

int main() {
  uart_init();
  kprintln("evset_ct in lru cache TESTS %d", TESTS);
  kprintln("config  slow fails aver_acc fast fails aver_acc faster fails aver_acc");

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

    fails[0]    = 0; fails[1]    = 0; fails[2]    = 0;
    accesses[0] = 0; accesses[1] = 0; accesses[2] = 0;
    for(uint16_t tests = 0, speed = 0; tests < TESTS * 3; tests++) {
      if(tests >=   TESTS) speed = 1;
      if(tests >= 2*TESTS) speed = 2;
      target[0]   = (cacheBlock_t*)TEST_TARGET;// + tests;
      probe       = (cacheBlock_t*)TEST_START;
      for(evsize = 0; evsize < WAYS; ) {
        for(prepare(speed, evsize, target); clcheck_f((void *)target[1]); access((void *)probe++)) accesses[speed] ++;
        accessWithfence((void *)target[1]);
        evset[evsize++] = probe - 1;
      }

      //check
      //in speed 2 before test drain out first
      if(speed == 2) for(access((void *)target[0]); clcheck_f((void *)target[0]); access((void *)probe++), accesses[speed] ++);
      if(evset_test((void *)target[0], 2) < 2) fails[speed] ++;
    }

    accesses[0] = accesses[0]/TESTS;
    accesses[1] = accesses[1]/TESTS;
    accesses[2] = accesses[2]/TESTS;
    //kprintln("config  slow fails aver_acc fast fails aver_acc faster fails aver_acc");
    kprintln(" %d             %d      %d          %d      %d            %d       %d",
              configs,  fails[0], accesses[0], fails[1], accesses[1], fails[2], accesses[2]);
  }
  while(1);
}
