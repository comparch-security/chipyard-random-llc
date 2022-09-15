#include <stdio.h>
#include <stdint.h>
#include <math.h>
#include <string.h>
#include <stdbool.h>

#include "ps_evset.h"
#include "list/list_utils.h"

#include "../utils/cache_utils.h"
#include "../utils/misc_utils.h"
#include "../utils/colors.h"

#ifdef LLC_INCLUSIVE

////////////////////////////////////////////////////////////////////////////////
// Declaration of functions

int ps_evset_reduce(Elem **evset, char *victim, int len, int threshold);

////////////////////////////////////////////////////////////////////////////////
// Definition of functions

int
ps_evset(Elem **evset, char *victim, int len, uint64_t* page, int is_huge, int threshold)
{
  //int is_list_empty = 1; // append works the same for empty list, no special treatment needed
  int list_len = 0;
  int len_requested = len;
  int guess_index=0;
  int counter_attempt = 0;
  int i;
  int counter_guess, try_guesses, time;

  Elem *evset_last = NULL;
  *evset = NULL;
  uint64_t victim_phy = (uint64_t)virt2phy((void*)victim);
  //printf("victim V %p P %p\n", (void*)victim, (void*)victim_phy);

  //////////////////////////////////////////////////////////////////////////////
  // Create a guess pool

  uint64_t guess_pool[MAX_POOL_SIZE];
  
  int pool_size = (is_huge) ? MAX_POOL_SIZE_HUGE : MAX_POOL_SIZE_SMALL;
  #if RANDOMIZE_CACHE_SETINDEX == 1
  for (i=0; i<pool_size; i++)  {
    guess_pool[i] = ((uint64_t)page + ((uint64_t)victim & (CACHEBLOCK_PERIOD-1)) + i*CACHEBLOCK_PERIOD);
  }
  #elif RANDOMIZE_GUESS_POOL == 0
  for (i=0; i<pool_size; i++)  {
    guess_pool[i] = (is_huge) ?
      ((uint64_t)page + ((uint64_t)victim & (LLC_PERIOD-1      )) + i*LLC_PERIOD      ): 
      ((uint64_t)page + ((uint64_t)victim & (SMALLPAGE_PERIOD-1)) + i*SMALLPAGE_PERIOD);
  }
  #else
  // Potential improvement: randomization could be better (e.g., to avoid duplicates)
  for (i=0; i<pool_size; i++)  {
    guess_pool[i] = (is_huge) ?
      ((uint64_t)page + ((uint64_t)victim & (LLC_PERIOD-1      )) + (rand() % MAX_POOL_SIZE_HUGE )*LLC_PERIOD      ): 
      ((uint64_t)page + ((uint64_t)victim & (SMALLPAGE_PERIOD-1)) + (rand() % MAX_POOL_SIZE_SMALL)*SMALLPAGE_PERIOD);
  }
  #endif
 

  
  //////////////////////////////////////////////////////////////////////////////
  // Start finding eviction set


extend:
  while (list_len<len) {
    counter_guess = 0;
    try_guesses = true;

    // Place TARGET in LLC
    maccess((void*)victim);
    maccess((void*)victim);
    asm volatile("fence");

    if(disable_already_found==0) {
      traverse_zigzag_victim(*evset, (void*)victim); 
      traverse_zigzag_victim(*evset, (void*)victim); 
    }

    // Search 
    while (try_guesses) {

      // Access guess
      //maccess((void*)guess_pool[guess_index]);
      time_mread((void*)guess_pool[guess_index]);
      asm volatile("fence");

      // Measure TARGET
      /*
      if(enable_cacheline_check) {
        time = threshold+1;
        if(maccess_check(victim, (void*)victim_phy)) time = threshold>>2;
      } else {
        time = time_mread((void*)victim);
      }*/
      time = time_mread((void*)victim);

      // If TARGET is evicted
      #if IGNORE_VERY_SLOW == 1
      if (time>threshold && time < 2*threshold) { 
      #else
      if (time>threshold) { 
      #endif
        try_guesses = false;
        counter_attempt = 0;

        // Add the guess to linkedlist
        evset_last = (Elem*)guess_pool[guess_index];
        list_len = list_append(evset, evset_last);
        
        // Potential improvement: linked list for easy removal
        for (int i=guess_index; i<pool_size-1; i++){
          guess_pool[i] = guess_pool[i+1];
        }
        pool_size--;
      }
      else {
        guess_index++;
      }
    
      // Wrap around the pool
      if (guess_index>=pool_size-1) {
        guess_index = 0;
        try_guesses = false; // reinstate victim to be sure
        if (++counter_attempt>=MAX_ATTEMPT){ // If too many wrap-arounds, return with fail
          return PS_FAIL_CONSTRUCTION;
        }
      }
    }
  }

    #if ENABLE_EXTENSION 
    // If list of minimal size cannot evict victim, extend it
    if (!ps_evset_test(evset, victim, threshold, 10, EVTEST_MEDIAN)) { 
      if (++len<MAX_EXTENSION)
        goto extend; // Obtain one more address
      return PS_FAIL_EXTENSION;
    }
    #endif

    #if ENABLE_REDUCTION
    // If list needed to be extended, reduce it to minimal
    if (list_len > len_requested){
      if (!ps_evset_reduce(evset, victim, len_requested, threshold)){
        return PS_FAIL_REDUCTION;
      }
    }
    #endif

  traverse_list_fpga(*evset);
  traverse_list_fpga(*evset);
  traverse_list_fpga(*evset);
  traverse_list_fpga(*evset);
  if (!ps_evset_test(evset, victim, threshold, 3, EVTEST_ALLPASS))
    return PS_FAIL_FINAL_TEST;
  
  return PS_SUCCESS;
}

int
ppp_evset(Elem **evset, char *victim, int len, uint64_t* page, int is_huge, int threshold, int prime_len_min, int prime_len_max)
{
  #define CHECKS  55
  static uint64_t prime_index  = 0;

  int i,j,k;

  uint64_t prime, prime_start, prime_end, prime_len;
  uint64_t probe;
  uint64_t time;
  uint64_t mask;
  uint64_t *prb_mask=NULL, *prb_pool=NULL; 
  uint64_t  prun_len=0, prb1_len=0, prb2_len=0;
  uint64_t prime_len_try = prime_len_min;

  uint64_t max_pool_size = EVICT_LLC_SIZE / CACHEBLOCK_PERIOD;
  uint64_t offset = CACHEBLOCK_PERIOD;
  uint64_t victim_phy = (uint64_t)virt2phy((void*)victim);
  int timerecord[CHECKS];

  while(1) {
    prime_len = prime_len_try;
    if(prime_index + (prime_len << 1) + 1 > max_pool_size) prime_index = 0;
    if(prime_index + (prime_len << 1) + 1 > max_pool_size) return  PS_FAIL_CONSTRUCTION;
    prb_mask = (uint64_t*)malloc(sizeof(uint64_t)*(prime_len/64+1));
    for(i = 0; i < prime_len/64 + 1; i++) prb_mask[i] = 0;
  
    //drain and force LRU
    prime = ((uint64_t)page + ((uint64_t)victim & (CACHEBLOCK_PERIOD-1)) + prime_index*CACHEBLOCK_PERIOD);
    for(i = 0; i < prime_len; i++) {
      prime += offset;
      maccess((void*)prime);
    }
    prime_index  += prime_len;
  
    //prime
    maccess((void*)victim); maccess((void*)victim);
    prime_start = ((uint64_t)page + ((uint64_t)victim & (CACHEBLOCK_PERIOD-1)) + prime_index*CACHEBLOCK_PERIOD);
    for(i=0, prime=prime_start; i < prime_len; prime += offset) {
      maccess((void*)prime);
      prb_mask[i>>6] |= ((uint64_t)1 << (i & 0x3f));
      i++;
      if(enable_ppp_prime_clcheck) {
        if(clcheck((void*)victim_phy) == 0) break;
      }
    }
    prime_len    = i;
    prime_end    = prime;
    prime_index += prime_len;
  
    //prune: remove miss
    prun_len = prime_len;
    for(i = 0; i<1; i++) {
      probe = prime_start ;
      for(j = 0; j<prime_len; j++, probe += offset) {
        time = time_mread((void*)probe);
        if(time > threshold) { //remove miss
          if(((prb_mask[j>>6] >> (j & 0x3f)) & (uint64_t)0x01) == 0x01) {
            prun_len--;
            prb_mask[j>>6] &= ~((uint64_t)1 << (j & 0x3f));
          }
        }
      }
    }
  
    /*for(i=0, j=0, mask=0, prime=prime_start-offset; ; ) {
      do {
        mask = mask >> 1;
        if((i & 0x3f) == 0) mask = prb_mask[i>>6];
        i++; prime += offset;
      } while((mask & 0x01) == 0 && i < prime_len);
      if((mask & 0x01) == 0) break;
      j++; maccess((void*)prime);
      
    }
    if(j != prun_len) printf("prun_len missMatch %d!=%d\n", j, prun_len);*/
    maccess((void*)victim); maccess((void*)victim);
    //probe: remove hit
    for(i=0, mask=0, probe=prime_start-offset, prb1_len=0; ; ) {
      do {
        mask = mask >> 1;
        if((i & 0x3f) == 0) mask = prb_mask[i>>6];
        i++; probe += offset;
      } while((mask & 0x01) == 0 && i < prime_len);
      if((mask & 0x01) == 0) break;
      time = time_mread((void*)probe);
      if(time > threshold) {
        prb1_len++;
      } else { //remove hit
        prb_mask[(i-1)>>6] &= ~((uint64_t)1 << ((i-1) & 0x3f));
      }
    }
  
   /*for(i=0, j=0, mask=0, prime=prime_start-offset; ; ) {
      do {
        mask = mask >> 1;
        if((i & 0x3f) == 0) mask = prb_mask[i>>6];
        i++; prime += offset;
      } while((mask & 0x01) == 0 && i < prime_len);
      if((mask & 0x01) == 0) break;
      j++; maccess((void*)prime);
    }
    if(j != prb1_len) printf("prb1_len missMatch i %d prime_len %d %d!=%d\n", i, prime_len, j, prb1_len);*/
    maccess((void*)victim); maccess((void*)victim);
    //probe again: remove hit
    for(i=0, mask=0, probe=prime_start-offset, prb2_len=0; ; ) {
      do {
        mask = mask >> 1;
        if((i & 0x3f) == 0) mask = prb_mask[i>>6];
        i++; probe += offset;
      } while((mask & 0x01) == 0 && i < prime_len);
      if((mask & 0x01) == 0) break;
      time = time_mread((void*)probe);
      //time = threshold + 10;
      if(time > threshold) {
        prb2_len++;
      } else { //remove hit
        prb_mask[(i-1)>>6] &= ~((uint64_t)1 << ((i-1) & 0x3f));
      }
    }
    
    if(prb2_len < len*4) {
      prb_pool = (uint64_t*)malloc(sizeof(uint64_t)*(prb2_len+1));
      for(i=0, j=0, mask=0, prime=prime_start-offset; j < prb2_len; ) {
        do {
          mask = mask >> 1;
          if((i & 0x3f) == 0) mask = prb_mask[i>>6];
          prime += offset;
          i++;
        } while((mask & 0x01) == 0 && i < prime_len);
        if((mask & 0x01) == 0) break;
        prb_pool[j++] = prime;
      }
    }
  
    //check
    for(i = 0; i < CHECKS; i++) timerecord[i] = 0;
    for(i = 0; i < CHECKS && prb2_len < len*4; i++) {
      maccess((void*)victim);
      for(j=0; j<5; j++) {
        for(k = 0; k<prb2_len; k++) {
          maccess((void*)prb_pool[k]);
        }
      }
      time = time_mread((void*)victim);
      timerecord[i] = time;
    }
    qsort(timerecord, CHECKS, sizeof(int), comp);
  
    if(prb_mask != NULL) {free(prb_mask); prb_mask = NULL; }
    if(prb_pool != NULL) {free(prb_pool); prb_pool = NULL; }

    if(timerecord[0] > threshold) {
      //printf(GREEN"\tvictim %p/%p time:[%d-%d-%d] pool_size: [%d->%d->%d->%d]\n"NC,
      //  (void*)victim, (void*)victim_phy, timerecord[0], timerecord[CHECKS>>1], timerecord[CHECKS-1],
      //  (int)prime_len, (int)prun_len, (int)prb1_len, (int)prb2_len);
      return PS_SUCCESS;
    }
    else {
      //printf(RED"\tvictim %p/%p time:[%d-%d-%d] pool_size: [%d->%d->%d->%d]\n"NC,
      //  (void*)victim, (void*)victim_phy, timerecord[0], timerecord[CHECKS>>1], timerecord[CHECKS-1],
      //  (int)prime_len, (int)prun_len, (int)prb1_len, (int)prb2_len);
      if(prime_len_try == prime_len_max)  return PS_FAIL_FINAL_TEST;
      prime_len_try = prime_len_try + 10 + ((prime_len_max - prime_len_min) >> 3);
      if(prime_len_try >  prime_len_max)  prime_len_try = prime_len_max;
    }
  }
}

////////////////////////////////////////////////////////////////////////////////

int
ps_evset_premap(uint64_t* page) {
  int i;
  for (i=0; i<EVICT_LLC_SIZE/(8); i+=128)
    page[i] = 0x1;

  for (i=0; i<EVICT_LLC_SIZE/(8); i+=128)
    page[i] = 0x0;
}

////////////////////////////////////////////////////////////////////////////////

int
ps_evset_reduce(Elem **evset, char *victim, int len, int threshold){
  int list_len = list_length(*evset), i;

  for (i=0; i<list_len; i++) {

    // Pop the first element
    Elem* popped = list_pop(evset);

    // If the reduced list evicts the TARGET, popped element can be removed
    if (ps_evset_test(evset, victim, threshold, 10, EVTEST_MEDIAN)) {
      if (list_length(*evset) == len){ // If the reduced list is minimal, SUCCESS
      return 1; // SUCCESS
    }
  }
    else { // If not, append the popped element to the end of list, and try again
      list_append(evset, popped); 
  }     

  }

  return 0; // FAIL
}


int 
ps_evset_test(Elem **evset, char *victim, int threshold, int test_len, int test_method) {

  // Check, whether the reduced list can evict the TARGET 
    
  int test, time[test_len], time_acc=0;
  int i=0;

  // Place TARGET in LLC
  asm volatile("fence");
  maccess((void*)victim);
  asm volatile("fence");

  for (test=0; test<test_len; test++) {

    // Potential improvement: could be sped up
    traverse_list_fpga(*evset);
    traverse_list_fpga(*evset);
    traverse_list_fpga(*evset);
    traverse_list_fpga(*evset);
    asm volatile("fence");

    // Measure TARGET (and place in LLC for next iteration)
    time[test] = time_mread((void*)victim);
    time_acc += time[test];
  }

  if (test_method == EVTEST_MEAN) {

    return (time_acc/test_len)>threshold;
  
  } else if (test_method == EVTEST_MEDIAN) {

    qsort(time, test_len, sizeof(int), comp);
      
    return time[test_len/2]>threshold;
  
  } else if (test_method == EVTEST_ALLPASS) {

    int all_passed = 1;
    for (test=0; test<test_len; test++)
      if (all_passed && time[test]<threshold) 
        all_passed = 0;
      
    return all_passed;
  }
}

#endif
