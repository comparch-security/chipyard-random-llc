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
#define SPLIT     16/17
#define SELS       2
#define CUTDEPTHS  8

void main(int argc, char **argv) {

  set_core(0, "");
  nice(19);
  uint8_t    sel              ;
  uint8_t    hit[SELS]        ;
  uint8_t    initDone         ;
  uint8_t    initTrys[SELS]   ;
  uint32_t   i;
  uint32_t   j;
  uint32_t   k;
  uint16_t   tests      ;
  uint16_t   shrinks    ;
  uint8_t    failed     ;
  uint16_t   fails      ;
  uint16_t   loops      ;
  uint32_t   acctime    ;
  uint16_t   reqevsize          = 16   ;
  uint16_t   reqtest            = 1    ;
  uint16_t   targetOffsetLow    = 0    ;
  uint8_t    targetUpdate       = 0    ;
  uint16_t   targetOffsetHigh   = 1023 ;
  uint8_t    shrkFailSeqs[SELS] = {0}  ;
  uint8_t    drainFails[SELS]   = {0}  ;
  uint8_t    offset;
  clock_t    start_time, end_time;

  cacheBlock_t*    target                      ;
  cacheBlock_t*    reserve                     ;
  cacheBlock_t*    fill[SELS]                  ;
  cacheBlock_t*    fillStart[SELS]             ;
  cacheBlock_t*    cutSPoint[SELS][CUTDEPTHS]  ;
  cacheBlock_t*    cutEPoint[SELS][CUTDEPTHS]  ;
  cacheBlock_t*    drain                       ;
  cacheBlock_t*    drainStart                  ;
  uint32_t         evsize[SELS]                ;
  uint16_t         cutsize[SELS][CUTDEPTHS]    ;
  uint8_t          recover[SELS]               ;
  uint32_t         evsizeInit[SELS]            ;
  uint32_t         candidates[SELS]            ;
  register uint64_t delay;

  if(argc >= 3) {
    reqevsize          = strtol(argv[1],0 ,0);
    reqtest            = strtol(argv[2],0 ,0);
  }
  if(argc >= 5) {
    targetOffsetLow  = strtol(argv[3],0 ,0);
    targetOffsetHigh = strtol(argv[4],0 ,0);
  }
  if(argc == 6)     targetUpdate     = strtol(argv[5],0 ,0);
  if(targetOffsetLow > targetOffsetHigh) targetOffsetHigh = targetOffsetLow;
  if(reqevsize < WAYS  )  reqevsize = WAYS;
  if(reqtest > 1000)      reqtest   = 1000;

  uint32_t dev_fd = open("/dev/mem", O_RDWR);
  if(dev_fd < 0) { printf("open(/dev/mem) failed.\n"); return; }

  uint8_t *l2ctrl_base = (uint8_t *)mmap((void*)L2_CTRL_ADDR,     L2_CTRL_SIZE,    PROT_READ | PROT_WRITE, MAP_SHARED,              dev_fd, L2_CTRL_ADDR   );
  uint8_t *dram_base   = (uint8_t *)mmap((void*)DRAM_TEST_ADDR,   DRAM_TEST_SIZE,  PROT_READ | PROT_WRITE, MAP_SHARED,              dev_fd, DRAM_TEST_ADDR );
  if(l2ctrl_base == MAP_FAILED)  { printf("L2CTRL mmap_fail!\n"); return; }
  if(dram_base   == MAP_FAILED)  { printf("DRAM   mmap_fail!\n"); return; }
  printf("evset_ge PID %d in lru cache REQEVSIZE %d TESTS %d\n", getpid(), reqevsize, reqtest);

  fails    = 0;
  target   = (cacheBlock_t*)dram_base + targetOffsetLow;
  for(tests = 0; tests < reqtest; tests++) {
    if(tests != 0) {
      if(targetUpdate == 0)  target ++;
      else if(tests%2 == 0)  target ++;
    }
    start_time = clock();
    if(target > (cacheBlock_t*)dram_base + targetOffsetHigh) target = (cacheBlock_t*)dram_base + targetOffsetLow;
    drainStart    = (cacheBlock_t*)(dram_base + DRAM_TEST_SIZE) - 64*BLKS;
    drain         = drainStart;

    //initialize candidates
    fillStart[0] = (cacheBlock_t*)target;
    for(sel = 0; sel < SELS; sel++) {
      if(sel != 0) fillStart[sel] = fillStart[sel-1]->prev + 1;
      if(fillStart[sel]     == target) fillStart[sel] = fillStart[sel]+1;
      if(fillStart[sel] + 1 == target) fillStart[sel] = fillStart[sel]+2;
      for(initDone = 0, initTrys[sel] = 0; initDone == 0 && initTrys[sel] < 50; initTrys[sel]++) {
        //drain out
        if(drain > drainStart + 32*BLKS) drain = drainStart;
        for(j = 0; j < 2*BLKS; accessNofence((void*)drain++), j++);
        //create candidates
        //for(cacheBlock_t* p = fillStart[sel]; p < fillStart[sel] + 3*BLKS; p = p + 64) { if(p == target) p++; accessNofence((void*)p); }
        fill[sel] = fillStart[sel]; fill[sel]->prev = 0; fill[sel]->next = fill[sel] + 1; fill[sel]++; fill[sel]->prev = fill[sel]-1; evsize[sel] = 2;
        for(hit[sel] = 1, accessWithfence((void *)target); ; ) {
          offset = 1; if(fill[sel] + 1 == target) offset = 2;
          fill[sel]->next  = fill[sel] + offset; fill[sel] = fill[sel] + offset; fill[sel]->prev = fill[sel]-offset; evsize[sel]++;
          if(hit[sel] != 0) {
            if(clcheck_f((void *)target) == 0) hit[sel] = 0;
            acctime = timeAccessNoinline((void *)target);
            if(evsize[sel] > (BLKS << 1)) break;
          } else break;
        }
        fillStart[sel]->prev = fill[sel]; fill[sel]->next = fillStart[sel];
        //check
        for(j=0; j < 2*BLKS; accessNofence((void*)drain++), j++);
        for(j=0, initDone=1; ;j++) {
          hit[sel] = 1; if(clcheck_f((void *)target) == 0) hit[sel] = 0;
          acctime = timeAccessNoinline((void *)target);
          if(hit[sel] == 1 || j > 2) break;
          for(k = 0, fill[sel] = fillStart[sel]; k < evsize[sel]; k++)  fill[sel] = fill[sel]->next;
        }
        if(hit[sel] != 0) initDone = 0;
      }
    }
    reserve = fillStart[SELS - 1]->prev + 1; if(reserve == target) reserve ++;

    //drain, check and shrink
    loops      = 0;
    shrinks    = 0;
    drain      = drainStart;
    for(sel = 0; sel < SELS; sel++) {
      drainFails[sel] = 0;
      evsizeInit[sel] = evsize[sel];
      candidates[sel] = evsize[sel]*SPLIT;
      recover[sel]    = 0;
      for(i = 0; i < CUTDEPTHS; i++) cutsize[sel][i] = 0;
      for(j = rand() % (evsize[sel] >> 1); j > 0; j--) fillStart[sel] = fillStart[sel]->next;
    }
    for(i = 0; i < BLKS; i++) accessNofence((void*)drain++);
    for(sel = 0; loops < SELS*2000; loops++) {
      for(i=0; i < SELS; i++) { if(evsize[i] > reqevsize) break; } if(i == SELS) break;
      for(i=0; i < SELS; i++) { if(evsize[i] == WAYS    ) break; } if(i != SELS) break;
      //drain
      if(SELS == 1) {
        sel = 0;
        for(i = 0; ; i++) {
          accessNofence((void*)drain++);
          if(                         drainFails[0] <= 10 && i >=   BLKS + (BLKS >> 1)) break;
          if( 30  <  drainFails[0] && drainFails[0] <= 20 && i >= 2*BLKS              ) break;
          if(                                                i >= 3*BLKS              ) break;
        }
      } else {
        for(i = 0; i < evsize[sel]; i++) fill[sel] = fill[sel]->next;
        sel = (sel + 1) % SELS;
        if(drainFails[sel] !=0) {
          if(--sel > SELS) sel = SELS - 1;
          if(evsize[sel] > 3*SETS) for(i = 0; i < 3*SETS; i++) accessNofence((void*)drain++);
          else                     for(i = 0; i < evsize[sel]; i++) fill[sel] = fill[sel]->next;
          sel = (sel + 1) % SELS;
        }
      }

      //check
      for(j = 0; ; j++) {
        hit[sel] = 1; if(clcheck_f((void *)target) == 0) hit[sel] = 0;
        acctime = timeAccessNoinline((void *)target);
        if(hit[sel] == 1 || j > 1) break;
        for(k = 1, fill[sel] = fillStart[sel]; k < candidates[sel]; k++) fill[sel] = fill[sel]->next;
        accessWithfence((void *)fill[sel]);
      }
      //shrink
      if(j == 0) {
        if(++drainFails[sel] == 0) drainFails[sel] = 255;
      }
      else {
        if(hit[sel] == 0) {
          shrinks++;
          for(i = CUTDEPTHS - 1; i > 0; i--) { //LIFO: push
            cutsize  [sel][i]  = cutsize  [sel][i-1];
            cutSPoint[sel][i]  = cutSPoint[sel][i-1];
            cutEPoint[sel][i]  = cutEPoint[sel][i-1];
          }
          cutsize  [sel][0]      = evsize[sel] - candidates[sel];
          cutSPoint[sel][0]      = fill[sel]->next;                  fill[sel]->next        = fillStart[sel];
          cutEPoint[sel][0]      = fillStart[sel]->prev;             fillStart[sel]->prev   = fill[sel];
          evsize[sel]            = candidates[sel];                  candidates[sel]        = evsize[sel]*SPLIT;
          shrkFailSeqs[sel]      = 0;
          if(sel == 0 && recover[0] > 11) printf("-");
        } else {
          fillStart[sel]         = fill[sel]->next;
          if(++shrkFailSeqs[sel] == 255) shrkFailSeqs[sel] = 254;
        }
      }
      //recover
      if(shrkFailSeqs[sel] > WAYS)  {
        //rollback
        if(cutsize[sel][0] > 0) {
          if(sel == 0 && recover[0] > 10) printf("+");
          evsize[sel]      = evsize[sel]+cutsize[sel][0];
          fillStart[sel]->next->prev = cutEPoint[sel][0];    cutEPoint[sel][0]->next = fillStart[sel]->next;
          fillStart[sel]->next       = cutSPoint[sel][0];    cutSPoint[sel][0]->prev = fillStart[sel];
          for(j = 0; j < CUTDEPTHS-1; j++) {   //LIFO pop
            cutsize  [sel][j]  = cutsize  [sel][j+1];
            cutSPoint[sel][j]  = cutSPoint[sel][j+1];
            cutEPoint[sel][j]  = cutEPoint[sel][j+1];
          }
          cutsize[sel][CUTDEPTHS-1] = 0;
        }
        //expand fill linked-list
        if((recover[sel] > 10) && (recover[sel] % 8 == 0) && (evsize[sel] < BLKS + 2*SETS) && (reserve + BLKS < drainStart)) {
          if(sel == 0 && recover[0] > 10) printf("\033[0m\033[1;31m%s\033[0m", "+");
          fill[sel] = fillStart[sel]->prev; fill[sel]->next = reserve; fill[sel] = fill[sel]->next; evsize[sel]++;
          for(i = 0; i < 2*SETS; i++) {
            offset = 1; if(fill[sel] + 1 == target) offset = 2;
            fill[sel]->next  = fill[sel] + offset; fill[sel] = fill[sel] + offset; fill[sel]->prev = fill[sel]-offset; evsize[sel]++;
          }
          fillStart[sel]->prev = fill[sel]; fill[sel]->next = fillStart[sel]; reserve = fillStart[sel]->prev + 1; if(reserve == target) reserve ++;
        }
        //shuffle
        for(i = 0; i < 1; i++) {
          cutsize[sel][CUTDEPTHS-1] = rand() % (candidates[sel] - 1) + 1;
          if(candidates[sel] < 2*WAYS) cutsize[sel][CUTDEPTHS-1] = WAYS >> 1;
          if(cutsize[sel][CUTDEPTHS-1] == 0) break;
          for(j = rand() % evsize[sel]; j > 0; j--) fillStart[sel] = fillStart[sel]->next;
          for(j = cutsize[sel][CUTDEPTHS-1], fill[sel] = fillStart[sel]; j > 0; j--) fill[sel] = fill[sel]->next;
          cutSPoint[sel][CUTDEPTHS-1]      = fill[sel]->next;         fill[sel]->next        = fillStart[sel];
          cutEPoint[sel][CUTDEPTHS-1]      = fillStart[sel]->prev;    fillStart[sel]->prev   = fill[sel];
          for(j = rand() % evsize[sel]; j > 0; j--) fillStart[sel] = fillStart[sel]->next;
          fillStart[sel]->next->prev = cutEPoint[sel][CUTDEPTHS-1];    cutEPoint[sel][CUTDEPTHS-1]->next = fillStart[sel]->next;
          fillStart[sel]->next       = cutSPoint[sel][CUTDEPTHS-1];    cutSPoint[sel][CUTDEPTHS-1]->prev = fillStart[sel];
          cutsize[sel][CUTDEPTHS-1] = 0;
        }
        recover[sel]++;
        candidates[sel]    = evsize[sel]*SPLIT + 1;
        shrkFailSeqs[sel]  = 0;
        for(j = 0; j < BLKS; accessNofence((void*)drain++), j++);
      }
      if(drain > drainStart + 32*BLKS) drain = drainStart;
      if(candidates[sel] >= evsize[sel])  candidates[sel] = evsize[sel] - 1; 
      if(candidates[sel] <  WAYS       )  candidates[sel] = WAYS;
    }
    if(recover[0] > 11) printf("\n");

    failed  = 0;
    for(sel = 0; sel < SELS; sel++) {
      fill[sel] = fillStart[sel];
      if(drain > drainStart + 32*BLKS) drain = drainStart;
      for(j = 0; j < 4*BLKS; accessNofence((void*)drain++), j++);
      accessWithfence((void *)target);
      for(i = 0, hit[sel] = 0; i < 4; i++) {
        for(j = 0; j < 4; j++) 
          for(k = 0; k < evsize[sel]; k++) fill[sel] = fill[sel]->next;
        if(clcheck_f((void *)target) == 1) hit[sel] ++;
        acctime = timeAccessNoinline((void *)target);
      }
      if(fill[sel] != fillStart[sel]) { printf("missAlign!\n"); }
    }
    for(sel = 0; sel < SELS; sel++) { if(hit[sel] == 0 && evsize[sel] <= reqevsize) break; }
    if(sel == SELS) { failed = 1, fails++;}
    uint32_t recoverAdd    = 0;
    uint32_t evsizeInitAdd = 0;
    for(sel = 0; sel < SELS; sel++) {
      recoverAdd    = recoverAdd    + recover[sel];
      evsizeInitAdd = evsizeInitAdd + evsizeInit[sel];
    }
    end_time = clock();
    if(failed) printf("\033[0m\033[1;31m%s\033[0m", "fail:");
    else       printf("\033[0m\033[1;32m%s\033[0m", "succ:");
    printf("%p %4d/%4d %4.1f %3d/%4d/%4d", target, fails, tests+1, ((float)evsizeInitAdd)/BLKS, recoverAdd, shrinks, loops);
    for(sel = 0; sel < SELS; sel++) { printf(" [%d/%d/%5d/%4.1f/%2d/%3d/%2d]", hit[sel], evsize[sel], evsizeInit[sel], ((float)evsizeInit[sel])/BLKS, recover[sel], drainFails[sel], initTrys[sel]); }
    printf(" %3.2fs/%3.2fs", (float)(end_time - start_time)/CLOCKS_PER_SEC, (float)(end_time)/CLOCKS_PER_SEC/(tests+1));
    printf("\n");
    sched_yield();
  }

  close(dev_fd);
  munmap(l2ctrl_base, L2_CTRL_SIZE  );
  munmap(dram_base,   DRAM_TEST_ADDR);
  return;
}