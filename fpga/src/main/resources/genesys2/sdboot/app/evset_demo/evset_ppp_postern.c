// See LICENSE for license details.

#include "platform.h"
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>
#include <signal.h>
#include <fcntl.h>
#include <sys/mman.h>
#include <sched.h>

#include "misc_utils.h"
#include "cache_utils.h"
#include "list_struct.h"


uint32_t filter_by_probe(cacheBlock_t** start, uint8_t rmhit, uint32_t len, uint8_t reserves) {
  uint32_t candidates = 0;
  //uint32_t i          = 0;
  cacheBlock_t* p    ;
  cacheBlock_t* last ;
  if(*start != 0) {
    for(p = *start, last = 0; p != 0; p = p->next) {
      if(clcheck_f((void *)p) == 1 ) p->hit = 1; else p->hit = 0;
      if(candidates == 0           ) { p->prev = 0; *start = p; }
      if(last != 0                 ) last->next = p;
      //if( rmhit && i < reserves    ) p->hit = 0;
      //if(!rmhit && i < reserves    ) p->hit = 1;
      if( rmhit && p->hit == 0     ) { last = p; candidates++; }
      if(!rmhit && p->hit == 1     ) { last = p; candidates++; }
      //i++;
    }
    if(last       != 0) last->next = 0;
    if(candidates == 0) { (*start)->next=0; *start = 0; }
    //if(i != len) printf("missAlign %d %d %d\n", len, i, candidates);
  }

  return candidates;
}

void main(int argc, char **argv) {

  set_core(0, "");
  nice(19);
  uint8_t    hit[2]      ;
  uint8_t    trys[2]     ;
  uint16_t   trysAll[2]  ;
  uint32_t   i;
  uint32_t   j;
  uint32_t   k;
  uint16_t   tests      ;
  uint16_t   fails[2]   ;
  uint32_t   acctime    ;
  uint8_t    ctFlag[2]  ;
  uint16_t   reqtest            = 1    ;
  uint16_t   targetOffsetLow    = 0    ;
  uint16_t   targetOffsetHigh   = 1024 ;
  uint8_t    offset;
  uint8_t    speed  = 0;
  clock_t    start_time[2], end_time[2], all_time[2] = {0};


  cacheBlock_t*    target                      ;
  cacheBlock_t*    probe                       ;
  cacheBlock_t*    probeStart                  ;
  cacheBlock_t*    drain                       ;
  cacheBlock_t*    drainStart                  ;
  uint8_t          evsize                      ;
  uint32_t         candidates[2][4]            ;
  uint32_t         probeAgains[2]              ;
  register uint64_t delay;

  if(argc >= 2) {
    reqtest            = strtol(argv[1],0 ,0);
  }
  if(argc >= 4) {
    targetOffsetLow  = strtol(argv[3],0 ,0);
    targetOffsetHigh = strtol(argv[4],0 ,0);
  }
  if(targetOffsetLow > targetOffsetHigh) targetOffsetHigh = targetOffsetLow;
  if(reqtest > 6500)      reqtest   = 6500;

  uint32_t dev_fd = open("/dev/mem", O_RDWR);
  if(dev_fd < 0) { printf("open(/dev/mem) failed.\n"); return; }

  uint8_t *l2ctrl_base = (uint8_t *)mmap((void*)L2_CTRL_ADDR,     L2_CTRL_SIZE,    PROT_READ | PROT_WRITE, MAP_SHARED,              dev_fd, L2_CTRL_ADDR   );
  uint8_t *dram_base   = (uint8_t *)mmap((void*)DRAM_TEST_ADDR,   DRAM_TEST_SIZE,  PROT_READ | PROT_WRITE, MAP_SHARED,              dev_fd, DRAM_TEST_ADDR );
  if(l2ctrl_base == MAP_FAILED)  { printf("L2CTRL mmap_fail!\n"); return; }
  if(dram_base   == MAP_FAILED)  { printf("DRAM   mmap_fail!\n"); return; }
  printf("evset_ppp PID %d in lru cache TESTS %d\n", getpid(), reqtest);

  trysAll[0] = 0;  trysAll[1] = 0;
  fails[0]   = 0;  fails[1]   = 0;
  target       = (cacheBlock_t*)dram_base + targetOffsetLow;
  drainStart   = (cacheBlock_t*)(dram_base + DRAM_TEST_SIZE) - 16*BLKS;
  drain        = drainStart;
  for(i = 0; i < 3*BLKS; accessNofence((void *)drain++), i++);
  for(tests = 0; tests < reqtest; tests++) {
    target ++;
    if(target > (cacheBlock_t*)dram_base + targetOffsetHigh) target = (cacheBlock_t*)dram_base + targetOffsetLow;

    for(speed = 0; speed < 2; speed++) {
      start_time[speed] = clock();
      for(evsize = EVSIZE; evsize > 0; evset[--evsize] = NULL);
      for(trys[speed] = 0; trys[speed] < EVSIZE && evsize < EVSIZE - 2; ) {
        trys[speed]++;
        if(evsize == 0) probeStart = (cacheBlock_t*)dram_base;
        else probeStart = (cacheBlock_t*)evset[0];
        if(evsize == 0 && ((uint64_t)probeStart & 0X0fff) == ((uint64_t)target & 0X0fff)) probeStart = probeStart+1;
        //tlb
        if(evsize == 0) { probe = (cacheBlock_t*)((((uint64_t)probeStart >> 12) << 12) | ((uint64_t)target & 0X0fff)); }
        else {
          cacheBlock_t* p;
          for(i = 0; i<evsize; i++) {
            p = (cacheBlock_t*)((((uint64_t)evset[i] >> 12) << 12) | ((uint64_t)target & 0X0fff));
            if(p != target && p != (cacheBlock_t*)evset[i]) accessNofence((void*)p);
          }
          probe = (cacheBlock_t*)((((uint64_t)evset[evsize-1] >> 12) << 12) | ((uint64_t)target & 0X0fff));
        }
        for(cacheBlock_t* p = probe; p < probe + 2*BLKS; p = p + 64) { if(p != target) accessNofence((void*)p); }

        //prime
        accessWithfence((void *)target);
        probe = probeStart; probe->prev = 0; candidates[speed][0] = 1;
        if(evsize > 0) { for(; probe->next != 0; probe = probe->next); candidates[speed][0] = evsize; }
        for(hit[speed] = 1; hit[speed] == 1; ) {
          offset = 1; if(((uint64_t)(probe + 1) & 0x0fff) == ((uint64_t)target & 0x0fff)) offset = 2;
          probe->next = probe + offset; probe = probe->next; probe->prev = probe-offset; candidates[speed][0]++;
          if(clcheck_f((void *)target) == 0) hit[speed] = 0;
          acctime = timeAccessNoinline((void *)target);
          if(candidates[speed][0] > 2*BLKS) break;
        }
        probe->next = 0;
        if((SETS >> 1) < candidates[speed][0] && candidates[speed][0] < 2*BLKS) evset[evsize++] = (uint64_t*)probe;

        if(speed == 0) {
          //step 2 prune: remove miss
          for(probe = probeStart; probe != 0; probe = probe->next);
          candidates[0][1] = filter_by_probe(&probeStart, 0, candidates[0][0], 0);
          //step 3 probe: remove hit
          for(probe = probeStart; probe != 0; probe = probe->next);
          accessWithfence((void *)target);
          candidates[0][2] = filter_by_probe(&probeStart, 1, candidates[0][1], 0);
          //step 4 probe again: remove hit
          for(candidates[0][3] = candidates[0][2], probeAgains[0] = 0; candidates[0][3] > WAYS && probeAgains[0]<5; probeAgains[0]++) {
            for(probe = probeStart; probe != 0; probe = probe->next);
            accessWithfence((void *)target);
            candidates[0][3] = filter_by_probe(&probeStart, 1, candidates[0][3], 0);
          }
        } else {
          candidates[1][1] = filter_by_probe(&probeStart, 1, candidates[1][0], 0);
          candidates[1][2] = filter_by_probe(&probeStart, 0, candidates[1][1], 0);
          for(candidates[1][3] = candidates[1][2], probeAgains[1] = 0; candidates[1][3] > WAYS && probeAgains[1]<5; probeAgains[1]++) {
            if(probeAgains[1] & 0x01) {                                    candidates[1][3] = filter_by_probe(&probeStart, 0, candidates[1][3], 0); }
            else                      { accessWithfence((void *)target);   candidates[1][3] = filter_by_probe(&probeStart, 1, candidates[1][3], 0); }
          }
        }

        //check
        hit[speed] = 255; ctFlag[speed] = 255;
        for(j = 0; j < 2; j++) { for(k = 0, probe = probeStart; probe != 0; k++, probe = probe->next); }
        if(k != candidates[speed][3]) { printf("[%d/%d/%d] missAlign!\n", speed, candidates[speed][3], k); }
        if(WAYS <= candidates[speed][3]) {
          hit[speed] = 0;
          accessWithfence((void *)target);
          for(i = 0; i < 4; i++) {
            for(j = 0; j < 3; j++) { for(k = 0, probe = probeStart; probe != 0; k++, probe = probe->next); }
            if(clcheck_f((void *)target) == 1) hit[speed] ++;
            acctime = timeAccessNoinline((void *)target);
          }
        }
        if(hit[speed] == 0) break;
        if(evsize > WAYS) {
          hit[speed] = 0;
          for(j = 0; j < WAYS+1; j++) { for(k = 0; k < evsize; k++) { if(evset[k]!=0)  accessNofence(evset[k]); } }
          accessWithfence((void *)target);
          for(i = 0; i < 4; i++) {
            for(j = 0; j < 3; j++) { for(k = 0; k < evsize; k++) { if(evset[k]!=0)  accessNofence(evset[k]); } }
            if(clcheck_f((void *)target) == 1) hit[speed] ++;
            acctime = timeAccessNoinline((void *)target);
          }
          ctFlag[speed] = hit[speed];
        }
        if(hit[speed] == 0) break;
       
        //evset linked-list
        if(evsize != 0) { if((cacheBlock_t*)evset[evsize-1] >= drainStart - 4*BLKS) { evsize = 0; for(i =0 ; i<EVSIZE; i++) evset[i] = 0; } }
        if(evsize != 0) {
          probe = (cacheBlock_t *)evset[0]; probe->prev = 0;
          if(evsize > 1) {
            probe->next = (cacheBlock_t*)evset[1]; probe = probe->next;
            for(i = 1; i < evsize; ) {
               probe->prev  = (cacheBlock_t*)evset[i-1];
               if(++i == evsize) break;
               probe->next  = (cacheBlock_t*)evset[i];
               probe        = probe->next;
            }
          }
          probe->next = 0;
        }

        //drain
        if(drain > drainStart + 8*BLKS) drain = drainStart;
        if(evsize < WAYS) i = (WAYS - evsize)*SETS + 3*SETS;
        else {i = 3*SETS; if(rand() & 0x01) i = i+BLKS; }
        for( ; i > 1; accessNofence((void *)drain++), i--);
      }
      end_time[speed] = clock();
      all_time[speed] = all_time[speed] + end_time[speed] - start_time[speed];
      //drain out
      if(drain > drainStart + 8*BLKS) drain = drainStart;
      for(i = 0; i < 3*BLKS; accessNofence((void *)drain++), i++);
      sched_yield();
    }
  
    printf("%4d %p ", tests+1, target);
    for(speed = 0; speed < 2; speed++) {
      trysAll[speed] = trysAll[speed] + trys[speed];
      if(hit[speed]!=0)  { printf("\033[0m\033[1;31m%s\033[0m", "fail:"); fails[speed]++;}
      else                 printf("\033[0m\033[1;32m%s\033[0m", "succ:");
      printf("[%3d/%3d/%3d/%4.1f", fails[speed], hit[speed], trys[speed], (float)trysAll[speed]/(tests+1));
      printf("/%5.2fs/%5.2fs",     (float)(end_time[speed] - start_time[speed])/CLOCKS_PER_SEC, (float)all_time[speed]/CLOCKS_PER_SEC/(tests+1));
      printf("/%5d/%5d/%4d/%3d/%3d/%3d]",  candidates[speed][0], candidates[speed][1], candidates[speed][2], candidates[speed][3], probeAgains[speed], ctFlag[speed]);
    }
    printf("\n");
  }

  close(dev_fd);
  munmap(l2ctrl_base, L2_CTRL_SIZE  );
  munmap(dram_base,   DRAM_TEST_ADDR);
  return;
}