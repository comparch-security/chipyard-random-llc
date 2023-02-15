#define _GNU_SOURCE
#include <stdio.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sched.h>
#include <time.h>
#include <errno.h>
#include <sys/mman.h>
#include <assert.h>
#define ASSERT(x) assert(x != -1)

// Consider this file only if the target machine has inclusive caches 
// according to configuration.h
#include "configuration.h"

#ifdef LLC_INCLUSIVE 


#include "prime.h"
#include "../utils/colors.h"
#include "../utils/cache_utils.h"
#include "../utils/memory_utils.h"
#include "../utils/misc_utils.h"

// Evset functions
#include "../evsets/list/list_traverse.h"
#include "../evsets/list/list_utils.h"
#include "../evsets/ps_evset.h"

////////////////////////////////////////////////////////////////////////////////
// Memory Allocations
extern volatile uint64_t *shared_mem;
extern volatile uint64_t *synchronization;
extern volatile uint64_t *synchronization_params;

////////////////////////////////////////////////////////////////////////////////
// Function declarations

void test_eviction_set_creation();
void test_primescope();

void configure_thresholds(
  uint64_t target_addr, int* thrL1, int* thrLLC, int* thrRAM, int* thrDET);

////////////////////////////////////////////////////////////////////////////////

uint64_t *evict_mem;
void attacker_helper();

void attacker(int test_option) {

  ASSERT(mem_map_shared(&evict_mem, EVICT_LLC_SIZE, HUGE_PAGES_AVAILABLE));

  if (fork() == 0) {
    set_core(HELPER_CORE, "Attacker Helper");
    attacker_helper();
    return;
  }

  if (test_option == 0) test_primescope();
  else                  test_eviction_set_creation();

  ASSERT(munmap(evict_mem,  EVICT_LLC_SIZE));

  // Shut Down
  *synchronization = -1;
  sleep(1);
}

////////////////////////////////////////////////////////////////////////////////

void test_primescope() {
/*
  //////////////////////////////////////////////////////////////////////////////
  // Include the function macros
  #include "macros.h"

  //////////////////////////////////////////////////////////////////////////////
  // Pick a random target_addr from shared_mem

  int seed = time(NULL); srand(seed);
  int target_index = (rand()%1000)*8;
  
  uint64_t target_addr = (uint64_t)&shared_mem[target_index];

  //////////////////////////////////////////////////////////////////////////////
  // Cache Access Thresholds

  int thrL1, thrLLC, thrRAM, thrDET;
  configure_thresholds(target_addr, &thrL1, &thrLLC, &thrRAM, &thrDET);

  printf("\nThresholds Configured\n\n");
  printf("\tL1/L2    : %u\n", thrL1   );
  printf("\tLLC      : %u\n", thrLLC  );
  printf("\tRAM      : %u\n", thrRAM  );
  printf("\tTHRESHOLD: %u\n", thrDET  );

  // Only need helper for clean threshold calibration
  KILL_HELPER(); 

  //////////////////////////////////////////////////////////////////////////////
  // Eviction Set Construction

  #define EV_LLC LLC_WAYS

#if PREMAP_PAGES == 1
  ps_evset_premap(evict_mem);
#endif

  Elem  *evsetList;
  Elem **evsetList_ptr = &evsetList;

  *evsetList_ptr=NULL;

repeat_evset:
  if (PS_SUCCESS != ps_evset( evsetList_ptr,
                              (char*)target_addr,
                              EV_LLC,
                              evict_mem,
                              HUGE_PAGES_AVAILABLE,
                              thrDET))
    goto repeat_evset;

  printf("\nEviction set is constructed successfully\n\n");

  printf("\tEviction set addresses are: "); print_list(evsetList);

  //////////////////////////////////////////////////////////////////////////////
  // Prepare for Test

  // Convert the eviction set link-list to an array
  uint64_t evset[EV_LLC]; list_to_array(evsetList, evset);
  
  // Set its first element as the scope_addr
  uint64_t scope_addr = evset[0];

  //////////////////////////////////////////////////////////////////////////////
  // Prime+Scope (Toy Example)

  printf("\nTesting Prime+Scope\n\n");

  int access_time, success=0;

  for (int t=0; t<TEST_LEN; t++) {

    PRIME(evset);
    TIME_READ_ACCESS(scope_addr);
    TIME_READ_ACCESS(scope_addr);

    // Sanity check: the scope_addr is in low-level cache (L1).

    if (access_time<thrLLC)  
          printf(GREEN"\tSuccess at test %d\n"NC, t);
    else  printf(RED  "\tFailure at test %d\n"NC, t);

    ////////////////////////////////////////////////////////////////////////////

    PRIME(evset);
    TIME_READ_ACCESS(scope_addr);    // SCOPE 
    TIME_READ_ACCESS(scope_addr);    // SCOPE
    VICTIM_READ_ACCESS(target_addr); // Cross-core access to monitored set
    TIME_READ_ACCESS(scope_addr);    // SCOPE detects access

    // The victim access to the target address should evict the scope_addr from all cache levels.

    if (access_time>thrLLC) { 
          success++;
          printf(GREEN"\tSuccess at test %d\n\n"NC, t);}
    else  printf(RED  "\tFailure at test %d\n\n"NC, t);
  }

  printf("\tSuccess %d/%d\n", success, TEST_LEN);
  */
}

void test_eviction_set_creation() {

  #define MAX_RETRY 0
  float timerecord[TIMERECORD], timeLo, timeMedi, timeHi;
  timeLo = 0; timeMedi = 0; timeHi = 0;
  //////////////////////////////////////////////////////////////////////////////
  // Include the function macros
  #include "macros.h"

  //////////////////////////////////////////////////////////////////////////////
  // Eviction Set Construction

  printf("\nTesting Eviction Set Construction Performance ");
  if(ppp_prime_len_min > 0) {
    printf("PPP PRIME_LEN [%d-%d]", ppp_prime_len_min, ppp_prime_len_max);
  } else {
    printf("CT");
    if(enable_already_found)   printf(" ENABLE_ALREADY_FOUND");
    else                       printf(" DISABLE_ALREADY_FOUND");
    if(enable_cacheline_check) printf(" ENABLE_CACHELINE_CHECK");
  }

  printf("\n\n");

  int attempt_counter, succ, fail, rv;
  succ=0; fail=0;
  struct timespec tstart={0,0}, tend={0,0}; double timespan;
  uint64_t timeAll=0;

  ////////////////////////////////////////////////////////////////////////////
  // Cache Access Thresholds

  uint64_t target_addr = (uint64_t)&shared_mem[0];
  int thrLLC, thrRAM, thrDET, thrL1;
  configure_thresholds(target_addr, &thrL1, &thrLLC, &thrRAM, &thrDET);

  // Only need helper for clean threshold calibration
  KILL_HELPER();
  int access = 0;

  for (int t=0; t<test_len; t++) {
    ////////////////////////////////////////////////////////////////////////////
    // Pick a new random target_addr from shared_mem

    int seed = time(NULL); srand(seed);
    int target_index = (rand()%1000)*8;
    if(target_index >= SHARED_MEM_SIZE) target_index = target_index - 8;
    target_addr = (uint64_t)&shared_mem[target_index];
    maccess((void*)target_addr); maccess((void*)target_addr);
    //printf("target_addr %p/%p\n", (void*)target_addr, (void*)virt2phy((void*)target_addr));


    ////////////////////////////////////////////////////////////////////////////
    // Eviction Set Construction

    #define EV_LLC LLC_WAYS

  #if PREMAP_PAGES == 1
    ps_evset_premap(evict_mem);
  #endif

    Elem  *evsetList;
    Elem **evsetList_ptr = &evsetList;

    *evsetList_ptr=NULL;

    attempt_counter = 0;

    clock_gettime(CLOCK_MONOTONIC, &tstart);
  repeat_evset:
    if(ppp_prime_len_min == 0) {
      rv =  ps_evset( evsetList_ptr,
                      (char*)target_addr,
                      EV_LLC,
                      evict_mem,
                      HUGE_PAGES_AVAILABLE,
                      thrDET,
                      &access);
    } else {
      rv =  ppp_evset( evsetList_ptr,
                       (char*)target_addr,
                       EV_LLC,
                       evict_mem,
                       HUGE_PAGES_AVAILABLE,
                       thrDET,
                       ppp_prime_len_min,
                       ppp_prime_len_max,
                       &access);
    }
    if (rv != PS_SUCCESS) {
      if (++attempt_counter < MAX_RETRY)
        goto repeat_evset;
    }
    clock_gettime(CLOCK_MONOTONIC, &tend);

    timespan = time_diff_ms(tstart, tend);
    timeAll += timespan;
    timerecord[t%TIMERECORD] = timespan;
    if(t%TIMERECORD == TIMERECORD - 1) {
      qsort(timerecord, TIMERECORD, sizeof(float), comp);
      timeLo    = timerecord[0]/1000;
      timeMedi  = timerecord[TIMERECORD>>1]/1000;
      timeHi    = timerecord[TIMERECORD-1]/1000;
    }
    if (attempt_counter ==0 || attempt_counter<MAX_RETRY) {
      succ++;
      printf(GREEN"\r\tPID %d Success. succ/try %d/%d acc %d Constucted with %d retries aver %5.3fs [%5.3f-%5.3f-%5.3f]s"NC,
        getpid(), succ, t+1, access, attempt_counter, (double)timeAll/1000/(t+1), (double)timeLo, (double)timeMedi, (double)timeHi);
      if(succ<=2 || succ==10 || t==100) printf("\n");
    }
    else {
      fail++;
      printf(RED"\r\tPID %d Fail.rv %d succ/try %d/%d  acc %d Could not construct aver %5.2fs [%5.3f-%5.3f-%5.3f]s"NC,
        getpid(), rv, succ, t+1, access, (double)timeAll/1000/(t+1), (double)timeLo, (double)timeMedi, (double)timeHi);
      if(fail<=2 || fail==10 || t==100) printf("\n");
    }
    if(enable_debug_log) {
      printf("\n\rVictim %p Eviction set addresses are: \n", (void*)target_addr); print_list(evsetList);
    }
    fflush(stdout);
  }
  printf("\n");
}


void configure_thresholds(
  uint64_t target_addr, int* thrL1, int* thrLLC, int* thrRAM, int* thrDET) {

  #define THRESHOLD_TEST_COUNT 5000

  int timing[10][THRESHOLD_TEST_COUNT];
  int access_time;

  #include "macros.h"
  Elem target;
  Elem *target_adr = &target;
  uint64_t target_adr_phy = (uint64_t)virt2phy((void*)target_adr);
  //printf("PID %d Vir:%p/Phy:%p \n", getpid(), (void*)target_adr, (void*)target_adr_phy);

  //printf("PID %d Vir:%p/Phy:%p\n", getpid(), &target_addr, virt2phy(&target_addr));
  sched_yield();
  for (int t=0; t<THRESHOLD_TEST_COUNT; t++) {
    //FLUSH             (target_adr_phy);
    //HELPER_READ_ACCESS(target_adr);
    READ_ACCESS(target_adr);
    FLUSHL1(target_adr_phy);
    //__asm__("nop"); __asm__("nop"); __asm__("nop"); __asm__("nop");
    TIME_READ_ACCESS  (target_adr); timing[0][t] = access_time; // time0: LLC
    FLUSH             (target_adr_phy);
    //__asm__("nop"); __asm__("nop"); __asm__("nop"); __asm__("nop");
    TIME_READ_ACCESS  (target_adr); timing[1][t] = access_time; // time1: DRAM
    TIME_READ_ACCESS  (target_adr); timing[2][t] = access_time; // time2: L1/L2
  }
  qsort(timing[0], THRESHOLD_TEST_COUNT, sizeof(int), comp);
  qsort(timing[1], THRESHOLD_TEST_COUNT, sizeof(int), comp);
  qsort(timing[2], THRESHOLD_TEST_COUNT, sizeof(int), comp);
  *thrLLC = timing[0][(int)0.10*THRESHOLD_TEST_COUNT];
  *thrRAM = timing[1][(int)0.50*THRESHOLD_TEST_COUNT];
  *thrL1  = timing[2][(int)0.10*THRESHOLD_TEST_COUNT];
  *thrDET = (2*(*thrRAM) + (*thrLLC))/3;
  if(*thrDET < timing[1][0])  *thrDET = timing[1][0] - 1; //*thrDET = (*thrDET + 2*timing[1][0]) / 3;

  printf("\nThresholds Configured\n\n");
  printf("\tL1/L2    : %d  [%3d-%3d-%3d-%3d-%3d-%3d-%3d-%3d-%3d-%3d-%3d]\n", *thrL1,  timing[2][0],
              timing[2][(int)0.10*THRESHOLD_TEST_COUNT],  timing[2][(int)0.20*THRESHOLD_TEST_COUNT], timing[2][(int)0.30*THRESHOLD_TEST_COUNT],
              timing[2][(int)0.40*THRESHOLD_TEST_COUNT],  timing[2][(int)0.50*THRESHOLD_TEST_COUNT], timing[2][(int)0.60*THRESHOLD_TEST_COUNT],
              timing[2][(int)0.70*THRESHOLD_TEST_COUNT],  timing[2][(int)0.80*THRESHOLD_TEST_COUNT], timing[2][(int)0.90*THRESHOLD_TEST_COUNT],
              timing[2][THRESHOLD_TEST_COUNT-1]);
  printf("\tLLC      : %d  [%3d-%3d-%3d-%3d-%3d-%3d-%3d-%3d-%3d-%3d-%3d]\n", *thrLLC, timing[0][0],
              timing[0][(int)0.10*THRESHOLD_TEST_COUNT],  timing[0][(int)0.20*THRESHOLD_TEST_COUNT], timing[0][(int)0.30*THRESHOLD_TEST_COUNT],
              timing[0][(int)0.40*THRESHOLD_TEST_COUNT],  timing[0][(int)0.50*THRESHOLD_TEST_COUNT], timing[0][(int)0.60*THRESHOLD_TEST_COUNT],
              timing[0][(int)0.70*THRESHOLD_TEST_COUNT],  timing[0][(int)0.80*THRESHOLD_TEST_COUNT], timing[0][(int)0.90*THRESHOLD_TEST_COUNT],
              timing[0][THRESHOLD_TEST_COUNT-1]);
  printf("\tRAM      : %d  [%3d-%3d-%3d-%3d-%3d-%3d-%3d-%3d-%3d-%3d-%3d]\n", *thrRAM, timing[1][0],
              timing[1][(int)0.10*THRESHOLD_TEST_COUNT],  timing[1][(int)0.20*THRESHOLD_TEST_COUNT], timing[1][(int)0.30*THRESHOLD_TEST_COUNT],
              timing[1][(int)0.40*THRESHOLD_TEST_COUNT],  timing[1][(int)0.50*THRESHOLD_TEST_COUNT], timing[1][(int)0.60*THRESHOLD_TEST_COUNT],
              timing[1][(int)0.70*THRESHOLD_TEST_COUNT],  timing[1][(int)0.80*THRESHOLD_TEST_COUNT], timing[1][(int)0.90*THRESHOLD_TEST_COUNT],
    timing[1][THRESHOLD_TEST_COUNT-1]);
  printf("\tTHRESHOLD: %d  \n", *thrDET  );


  //*thrDET = *thrRAM-1;
}

#endif