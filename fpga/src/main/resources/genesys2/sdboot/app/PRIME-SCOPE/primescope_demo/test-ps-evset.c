#define _GNU_SOURCE
#include <stdio.h>
#include <stdint.h>
#include <stdlib.h>
#include <malloc.h>
#include <sys/mman.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>
#include <sched.h>
#include <getopt.h>
#include <assert.h>
#define ASSERT(x) assert(x != -1)

#include "configuration.h"
#include "../utils/memory_utils.h"
#include "../utils/misc_utils.h"

#include "../evsets/ps_evset.h"

////////////////////////////////////////////////////////////////////////////////
// Memory Allocations

uint64_t *shared_mem;
volatile uint64_t *synchronization;
volatile uint64_t *synchronization_params;
extern   uint64_t *evict_mem;

////////////////////////////////////////////////////////////////////////////////
// Function declarations

void victim();
void attacker_helper();
void test_eviction_set_creation();

////////////////////////////////////////////////////////////////////////////////

int main(int argc, char **argv)
{
  disable_already_found      = 0;
  enable_cacheline_check     = 0;
  enable_debug_log           = 0;
  ppp_prime_len_min          = 0;
  ppp_prime_len_max          = 0;
  enable_ppp_prime_clcheck   = 0;
  open_devmem_selfpage();
  while (1) {
    int option_index=0;
    static struct option long_options[] = {
      {"disable_already_found"           , no_argument,          0,  0},
      {"enable_cacheline_check"          , no_argument,          0,  0},
      {"enable_debug_log"                , no_argument,          0,  0},
      {"ppp_prime_len_min"               , required_argument,    0,  0},
      {"ppp_prime_len_max"               , required_argument,    0,  0},
      {"enable_ppp_prime_clcheck"        , no_argument,          0,  0},
      {0                                 , 0,                    0,  0}};

    if (getopt_long(argc, argv, "", long_options, &option_index) == -1)
      break;

    if(option_index == 0) disable_already_found             = 1 ;
    if(option_index == 1) enable_cacheline_check            = 1 ;
    if(option_index == 2) enable_debug_log                  = 1 ;
    if(option_index == 3) ppp_prime_len_min                 = atoi(optarg);
    if(option_index == 4) ppp_prime_len_max                 = atoi(optarg);
    if(option_index == 5) enable_ppp_prime_clcheck          = 1;
  }
  if(ppp_prime_len_min > ppp_prime_len_max) ppp_prime_len_max = ppp_prime_len_min +  (ppp_prime_len_min >> 1);
  //////////////////////////////////////////////////////////////////////////////
  // Memory allocations

  // `shared_mem` is for addresses that the attacker and victim will share.
  // `synchronization*` are variables for communication between threads.

  ASSERT(mem_map_shared(&shared_mem, SHARED_MEM_SIZE, HUGE_PAGES_AVAILABLE));
  ASSERT(var_map_shared(&synchronization));
  ASSERT(var_map_shared(&synchronization_params));

  *shared_mem = 1;
  *synchronization = 0;

  if (fork() == 0) {
    set_core(HELPER_CORE, "Attacker Helper");
    attacker_helper();
    return 0;
  }

  ASSERT(mem_map_shared(&evict_mem, EVICT_LLC_SIZE, HUGE_PAGES_AVAILABLE));
  set_core(ATTACKER_CORE, "Attacker");
  test_eviction_set_creation();

  //////////////////////////////////////////////////////////////////////////////
  // Memory de-allocations

  ASSERT(munmap(shared_mem, SHARED_MEM_SIZE));
  ASSERT(var_unmap(synchronization));
  ASSERT(var_unmap(synchronization_params));

  close_devmem_selfpage();
  printf("finish test-ps-evset\n");

  return 0;
}