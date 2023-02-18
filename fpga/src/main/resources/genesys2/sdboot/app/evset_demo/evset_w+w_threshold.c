// See LICENSE for license details.

#include "platform.h"
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>
#include <signal.h>
#include <fcntl.h>
#include <sys/mman.h>

#include "misc_utils.h"
#include "cache_utils.h"
#include "list_struct.h"


void test_ww_conflict_time(uint64_t target, uint64_t non_congruent_addr)
{
  int timing[10][26];
  int access_time;
  int i =0 ;
  int j =0 ;
  int k =0 ;

  static int tests=0;
  static long long totaltiming[10] = {0,0,0,0,0,0,0,0,0,0};



 /* for(i = 0; i < 1000000; i++) {
    clflush_f((void*)addr1); 
    clflush_f((void*)addr2);
    clflush_f((void*)addr3);
    clflush_f((void*)addr4);
    time_continue_mread((void*)addr1, (void*)addr2);
    time_continue_mread((void*)addr3, (void*)addr4);
    if(clcheck_f((void*)addr1) == 0) printf("error_check_addr1 %p\n", (void*)addr1);
    if(clcheck_f((void*)addr2) == 0) printf("error_check_addr2 %p\n", (void*)addr2);
    if(clcheck_f((void*)addr3) == 0) printf("error_check_addr3 %p\n", (void*)addr3);
    if(clcheck_f((void*)addr4) == 0) printf("error_check_addr4 %p\n", (void*)addr4);
    clflush_f((void*)addr1); 
    clflush_f((void*)addr2);
    clflush_f((void*)addr3);
    clflush_f((void*)addr4);
    if(clcheck_f((void*)addr1) != 0) printf("error_flush_addr1 %p\n", (void*)addr1);
    if(clcheck_f((void*)addr2) != 0) printf("error_flush_addr2 %p\n", (void*)addr2);
    if(clcheck_f((void*)addr3) != 0) printf("error_flush_addr3 %p\n", (void*)addr3);
    if(clcheck_f((void*)addr4) != 0) printf("error_flush_addr4 %p\n", (void*)addr4);
  }*/

  if(EVSIZE < 64 || EVSIZE < 26) {
    printf("EVSIZE too small\n");
    exit(0);
  }
  for(i = 0; i < 10; i++) { for(j =  0; j < EVSIZE; accessWithfence(evset[j++])); }
  for(i = 0; i < 10; i++) { for(j = 26; j < EVSIZE; accessWithfence(evset[j++])); }
  for(i = 0; i < 26; i++) {
    uint64_t congruent_addr = (uint64_t)evset[i];

    //style 1
    accessNofence((void*)target);
    accessNofence((void*)congruent_addr);
    clflush_f((void*)target);
    clflush_f((void*)congruent_addr);

    // time between accessing two addresses in same set.
    //time_continue_mread
    //time_continue_mwrite
    //timing[0][i] = time_continue_mread((void*)addr1, (void*)addr2);
    accessNofence((void*)congruent_addr);
    timing[0][i] = timeAccess((void*)target);
    if(timing[0][i] < 200) totaltiming[0] = totaltiming[0] + timing[0][i];

    // time between accessing two addresses in different set.
    //timing[1][i] = time_continue_mread((void*)addr3, (void*)addr4);
    //for(j = 0; j < 3; j++) { for(k =4; k < WAYS && evset[k] != 0; accessWithfence(evset[k++])); }

    accessNofence((void*)target);
    accessNofence((void*)non_congruent_addr);
    clflush_f((void*)target);
    clflush_f((void*)non_congruent_addr);
    accessNofence((void*)non_congruent_addr);
    timing[1][i] = timeAccess((void*)target);
    if(timing[1][i] < 200) totaltiming[1] = totaltiming[1] + timing[1][i];


    //style 2
    accessNofence((void*)target);
    accessNofence((void*)congruent_addr);
    clflush_f((void*)target);
    clflushl1_f((void*)congruent_addr);
    accessNofence((void*)congruent_addr);
    timing[2][i] = timeAccess((void*)target);
    if(timing[2][i] < 200) totaltiming[2] = totaltiming[2] + timing[2][i];

    accessNofence((void*)target);
    accessNofence((void*)non_congruent_addr);
    clflush_f((void*)target);
    clflushl1_f((void*)non_congruent_addr);
    accessNofence((void*)non_congruent_addr);
    timing[3][i] = timeAccess((void*)target);
    if(timing[3][i] < 200) totaltiming[3] = totaltiming[3] + timing[3][i];

    //style 3
    accessNofence((void*)target);
    accessNofence((void*)congruent_addr);
    clflushl1_f((void*)target);
    clflush_f((void*)congruent_addr);
    accessNofence((void*)congruent_addr);
    timing[4][i] = timeAccess((void*)target);
    if(timing[4][i] < 200) totaltiming[4] = totaltiming[4] + timing[4][i];

    accessNofence((void*)target);
    accessNofence((void*)non_congruent_addr);
    clflushl1_f((void*)target);
    clflush_f((void*)non_congruent_addr);
    accessNofence((void*)non_congruent_addr);
    timing[5][i] = timeAccess((void*)target);
    if(timing[5][i] < 200) totaltiming[5] = totaltiming[5] + timing[5][i];

    //style 4
    accessNofence((void*)target);
    accessNofence((void*)congruent_addr);
    clflushl1_f((void*)target);
    clflushl1_f((void*)congruent_addr);
    accessNofence((void*)congruent_addr);
    timing[6][i] = timeAccess((void*)target);
    if(timing[6][i] < 200) totaltiming[6] = totaltiming[6] + timing[6][i];

    accessNofence((void*)target);
    accessNofence((void*)non_congruent_addr);
    clflushl1_f((void*)target);
    clflushl1_f((void*)non_congruent_addr);
    accessNofence((void*)non_congruent_addr);
    timing[7][i] = timeAccess((void*)target);
    if(timing[7][i] < 200) totaltiming[7] = totaltiming[7] + timing[7][i];    

    tests++;
  }
  printf("tests %d\n", tests);
  printf("Style 1 Access in same set:  totaltiming %5ld  /", totaltiming[0]); for(i = 0; i < 26; i++) { printf("%4d-", timing[0][i]); } printf("\n");
  printf("Style 1 Access in diff set:  totaltiming %5ld  /", totaltiming[1]); for(i = 0; i < 26; i++) { printf("%4d-", timing[1][i]); } printf("\n");
  printf("Style 2 Access in same set:  totaltiming %5ld  /", totaltiming[2]); for(i = 0; i < 26; i++) { printf("%4d-", timing[2][i]); } printf("\n");
  printf("Style 2 Access in diff set:  totaltiming %5ld  /", totaltiming[3]); for(i = 0; i < 26; i++) { printf("%4d-", timing[3][i]); } printf("\n");
  printf("Style 3 Access in same set:  totaltiming %5ld  /", totaltiming[4]); for(i = 0; i < 26; i++) { printf("%4d-", timing[4][i]); } printf("\n");
  printf("Style 3 Access in diff set:  totaltiming %5ld  /", totaltiming[5]); for(i = 0; i < 26; i++) { printf("%4d-", timing[5][i]); } printf("\n");
  printf("Style 4 Access in same set:  totaltiming %5ld  /", totaltiming[6]); for(i = 0; i < 26; i++) { printf("%4d-", timing[6][i]); } printf("\n");
  printf("Style 4 Access in diff set:  totaltiming %5ld  /", totaltiming[7]); for(i = 0; i < 26; i++) { printf("%4d-", timing[7][i]); } printf("\n");
  
}

void prepare(uint8_t speed, uint8_t evsize, cacheBlock_t** evtarget) { //fill congruent addresses
  if(speed < 2) {
    *(evtarget + 1) = *evtarget;
    accessWithfence((void *)(*evtarget));
    if(speed == 1) {
      for(uint8_t j = 0; j < evsize; j++) {
        accessWithfence((void *)(*evtarget));
        if(j >= (WAYS - 1)) break;
        accessWithfence((void *)(evset[j]));
      }
    }
  }
}

void main(int argc, char **argv) {

  set_core(0, "");
  nice(19);
  uint32_t   i;
  uint32_t   j;
  uint32_t   k;
  uint8_t    speed   ;
  uint8_t    evsize[2]     ;
  uint32_t   evsizeAll[2]  ;
  uint32_t   access[2]     ;
  uint32_t   accessAll[2]  ;
  uint8_t    failed  ;
  uint16_t   fails[2];
  uint32_t   acctime;
  uint8_t    hit[2]    ;
  uint16_t   reqtest   ;
  uint16_t   reqspeed  ;
  cacheBlock_t*    target[2];
  cacheBlock_t*    probe[2];
  cacheBlock_t*    drain;
  cacheBlock_t*    drainStart;
  clock_t    start_time[2], end_time[2], all_time[2] = {0};


  reqtest    = 1000;
  reqspeed   = 1;

  uint32_t dev_fd = open("/dev/mem", O_RDWR);
  if(dev_fd < 0) { printf("open(/dev/mem) failed.\n"); return; }

  uint8_t *l2ctrl_base = (uint8_t *)mmap((void*)L2_CTRL_ADDR,     L2_CTRL_SIZE,    PROT_READ | PROT_WRITE, MAP_SHARED,               dev_fd, L2_CTRL_ADDR   );
  uint8_t *dram_base   = (uint8_t *)mmap((void*)DRAM_TEST_ADDR,   DRAM_TEST_SIZE,  PROT_READ | PROT_WRITE, MAP_SHARED,               dev_fd, DRAM_TEST_ADDR );
  if(l2ctrl_base == MAP_FAILED)  { printf("L2CTRL mmap_fail!\n"); return; }
  if(dram_base   == MAP_FAILED)  { printf("DRAM   mmap_fail!\n"); return; }
  printf("l2ctrl_base %p\n", l2ctrl_base);
  printf("dram_base   %p\n", dram_base);

  target[0] = (cacheBlock_t*)dram_base;
  probe[0]  = target[0] + 1;
  printf("evset_ct in lru cache TESTS %d SPEED %d\n", reqtest, reqspeed);

  failed      = 1;
  fails[0]    = 0; fails[1]    = 0;
  target[0]   = (cacheBlock_t*)dram_base;
  probe[0]    = (cacheBlock_t*)dram_base;
  drainStart  = (cacheBlock_t*)(dram_base + DRAM_TEST_SIZE) - 32*BLKS;
  drain       = drainStart;
  evsizeAll[0] = 0; evsizeAll[1] = 0;
  accessAll[0] = 0; accessAll[1] = 0;
  for(uint16_t tests = 0, speed = reqspeed; tests < reqtest; tests++) {
    if(++target[0] > drainStart - 10) target[0] = (cacheBlock_t*)dram_base;
    for(i = 0; i<EVSIZE; i++) evset[i]=NULL;
    evsize[speed] = 0;
    for(speed = reqspeed; speed < 2; speed = speed + 10) {
      start_time[speed] = clock();
      access[speed] = 0;
      for(evsize[speed] = 0; evsize[speed] < EVSIZE; ) {
        if(++probe[0] >= drainStart - 2*BLKS) probe[0] = (cacheBlock_t*)dram_base;
        if(((uint64_t)(probe[0]) & 0x0fff) == ((uint64_t)target[0] & 0x0fff)) probe[0]++;

        //tlb
        /*for(cacheBlock_t* p = (cacheBlock_t*)((((uint64_t)probe[0] >> 12) << 12) | ((uint64_t)target[0] & 0X0fff)); p < probe[0] + 2*BLKS; p = p + 64) {
          if(p > drainStart - 1) break;
          accessNofence((void*)p);
        }*/

        //conflict test
        for(hit[speed] = 1, prepare(speed, evsize[speed], target); hit[speed] != 0; ) {
          if(++probe[0] == target[0]) probe[0]++;
          //if(((uint64_t)(++probe[0]) & 0x0fff) == ((uint64_t)target[0] & 0x0fff)) probe[0]++;
          if(probe[0] >= drainStart - 1) probe[0] = (cacheBlock_t*)dram_base;
          accessWithfence((void *)probe[0]);
          if(clcheck_f((void *)target[0]) == 0) hit[speed] = 0;
          acctime = timeAccessNoinline((void *)target[0]);
          access[speed]++; accessAll[speed]++;
          //if(60 < acctime && acctime < 130) hit[speed] = 0;
        }
        for(i = 0; i < evsize[speed]; i++) { if((uint64_t *)probe == evset[i]) break; }
        if(i == evsize[speed] && probe[0] != target[0]) evset[evsize[speed]++] = (uint64_t *)(probe[0]);

        //check
        hit[speed] = 255;
        if(evsize[speed] >= EVSIZE) {
          hit[speed] = 0;
          //if(drain > drainStart + 8*BLKS) drain = drainStart;
          //for(i = 0; i < SETS*3; accessNofence((void *)drain++), i++);
          for(i = 0; i < 2; i++) { for(j = 0; j < evsize[speed] && evset[j] != 0; accessWithfence(evset[j]), j++); }
          accessWithfence((void *)target[0]);
          for(i = 0; i < 4; i++) {
            for(j = 0; j < 3; j++) { for(k =0; k < evsize[speed] && evset[k] != 0; accessWithfence(evset[k++])); }
            if(clcheck_f((void *)target[0]) == 1) hit[speed] ++;
            acctime = timeAccessNoinline((void *)target[0]);
          }
        }
        if(hit[speed] == 0) break;
      }
      end_time[speed] = clock();
      all_time[speed] = all_time[speed] + end_time[speed] - start_time[speed];
      evsizeAll[speed]  = evsizeAll[speed] + evsize[speed];
      //drain out
      if(drain > drainStart + 8*BLKS) drain = drainStart;
      //for(i = 0; i < 3*BLKS; accessNofence((void *)drain++), i++);
      sched_yield();
    }

    /*printf("%4d %p %d ", tests+1, target[0], reqspeed);
    for(speed = reqspeed; speed < 2; speed = speed + 10) {
      if(hit[speed]!=0)  { printf("\033[0m\033[1;31m%s\033[0m", "fail:"); fails[speed]++;}
      else                 printf("\033[0m\033[1;32m%s\033[0m", "succ:");
      printf("[%3d/%3d/%3d/%5d/%9d/%4.1f", fails[speed], hit[speed], evsize[speed], access[speed], accessAll[speed], (float)evsizeAll[speed]/(tests+1));
      printf("/%5.2fs/%5.2fs]",     (float)(end_time[speed] - start_time[speed])/CLOCKS_PER_SEC, (float)all_time[speed]/CLOCKS_PER_SEC/(tests+1));
    }
    printf("\n");*/
    if(hit[speed] == 0) {
      probe[0] = probe[0] + (rand() & 0xfff);
      if(++probe[0] >= drainStart - 2*BLKS) probe[0] = (cacheBlock_t*)dram_base;
      test_ww_conflict_time((uint64_t)target[0], (uint64_t)probe[0]);
      probe[0] = probe[0] + (rand() & 0xfff);
      if(++probe[0] >= drainStart - 2*BLKS) probe[0] = (cacheBlock_t*)dram_base;
      test_ww_conflict_time((uint64_t)target[0], (uint64_t)probe[0]);
    }
  }

  munmap(l2ctrl_base, L2_CTRL_SIZE  );
  munmap(dram_base,   DRAM_TEST_SIZE);
  close(dev_fd);
  return;
}
