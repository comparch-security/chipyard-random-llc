#include "cache/cache.hpp"
#include "cache/algorithm.hpp"
#include "util/random.hpp"
#include <cstdio>
#include <unistd.h>
#include <time.h>

int main() {
  clock_t    start_time;
  printf("\rmain PID %d\n", getpid());
  init_cfg();
  randomize_seed();
  init_threads();
  int way = 16;
  int succ = 0, iter = 0, keep = 0;
  int csize = 1024*24;
  int way_pre = way;
  while (keep < 3 && iter < 200) {
    elem_t *candidate = allocate_list(csize);
    elem_t *victim = allocate_list(1);
    calibrate(victim);
    start_time = clock();
    while(!test_tar(candidate, victim)) {
      free_list(candidate);
      free_list(victim);
      candidate = allocate_list(csize);
      victim = allocate_list(1);
      calibrate(victim);
    }
    printf("victim %p\n", victim);
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
    printf("trials %d sucesses: %d keep: %d result: %d, way=%d time=%8.3fs\n", iter, succ, keep, rv, way, (float)(clock() - start_time)/CLOCKS_PER_SEC);
  }
  printf("the predicted way = %d\n", way);
  close_cfg();
  return 0;
}
