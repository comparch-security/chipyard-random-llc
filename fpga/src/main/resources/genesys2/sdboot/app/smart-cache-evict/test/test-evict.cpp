#include "cache/cache.hpp"
#include "cache/algorithm.hpp"
#include "util/random.hpp"
#include <cstdio>

int main() {
  init_cfg();
  randomize_seed();
  int way = 32;
  int succ = 0, iter = 0, keep = 0;
  int csize = 8000;
  int way_pre = way;
  while (keep < 3 && iter < 200) {
    elem_t *candidate = allocate_list(csize);
    elem_t *victim = allocate_list(1);
    calibrate(victim);
    while(!test_tar(candidate, victim)) {
      free_list(candidate);
      free_list(victim);
      candidate = allocate_list(csize);
      victim = allocate_list(1);
      calibrate(victim);
    }
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
    printf("trials %d sucesses: %d keep: %d result: %d, way=%d\n", iter, succ, keep, rv, way);
  }
  printf("the predicted way = %d\n", way);
  return 0;
}
