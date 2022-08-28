#include "cache/cache.hpp"
#include "cache/algorithm.hpp"
#include "util/random.hpp"
#include <cstdio>
#include <unistd.h>
#include <time.h>

int main() {
  clock_t    start_time, now_time, succ_time, succ_min_time;
  succ_time     = 0;
  succ_min_time = 1000*1000*1000;
  printf("\rmain PID %d\n", getpid());
  init_cfg();
  randomize_seed();
  init_threads();
  int i;
  int way = 16;
  int succ = 0, iter = 0, keep = 0;
  int csize = CFG.cache_set*(CFG.cache_way + (CFG.cache_way>>2));
  int way_pre = way;
  int cansize = 0;
  int mincansize = csize+100;
  if(CFG.elem_size == SZ_PG) csize = csize/(SZ_PG/SZ_CL);
  while (iter < 200) {
    elem_t *victim    = allocate_list(1);
    elem_t *candidate = allocate_list(csize);
    start_time = clock();
    for(i = 0; i<10; i++) {
      if(test_tar_pthread(candidate, victim, false)) break;
      free_list(candidate);
      free_list(victim);
      candidate = allocate_list(csize);
      victim = allocate_list(1);
    }
    printf("recreate %d victim %p\n",i, victim);
    bool rv = trim_tar_ran(&candidate, victim, way);
    free_list(candidate);
    free_list(victim);
    if(rv) {
      succ++;
      if(way_pre == way)
        keep++;
      else {
        keep = 0;
        way_pre = way;
      }
    }
    iter++;
    cansize += way;
    if(way < mincansize) mincansize = way;
    now_time = clock();
    if(rv) succ_time += (now_time - start_time);
    if(rv && (now_time - start_time) < succ_min_time) succ_min_time = (now_time - start_time);
    if(rv)  printf("\033[0m\033[1;32m%s\033[0m", "sucesses");
    else    printf("\033[0m\033[1;31m%s\033[0m", "sucesses");
    printf("/trials: %d/%d keep: %d cansize=%d Avercansize=%d Mincansize=%d Avertime=%6.2fs\n",
            succ, iter, keep, way, cansize/iter, mincansize, (float)(now_time)/iter/CLOCKS_PER_SEC);
  }
  //printf("the predicted way = %d\n", way);
  close_cfg();
  return 0;
}