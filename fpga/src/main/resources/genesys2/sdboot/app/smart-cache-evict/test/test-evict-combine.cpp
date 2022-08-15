#include "cache/cache.hpp"
#include "cache/algorithm.hpp"
#include "util/random.hpp"
#include <cstdio>

int main() {
  init_cfg();
  randomize_seed();
  init_threads();
  int way = 32;
  int succ = 0, iter = 0, keep = 0;
  int csize = 90000;
  int way_pre = way;
  while (keep < 5 && iter < 200) {
    elem_t *victim = allocate_list(1);
    elem_t *candidate = NULL;
    calibrate(victim);
    bool rv = trim_tar_combined_ran(&candidate, victim, way, csize, 600, 11);
    int m_way = way;
    if(rv) {
      way = trim_tar_final(&candidate, victim);
      rv = test_tar_pthread(candidate, victim, true);
      printf("verify result %d way = %d\n", rv, way);
    }
    free_list(candidate);
    free_list(victim);
    if(rv) {
      succ++;
      if(way_pre == way)
        keep++;
      else if(keep > 0)
        keep--;
      else if(way <= way_pre)
        way_pre = (way + way_pre) / 2;
      else
        way_pre++;
    }
    way = way_pre;
    iter++;
    printf("trials %d sucesses: %d keep: %d result: %d, way=%d\n", iter, succ, keep, rv, way);
  }
  printf("the predicted way = %d\n", way);
  return 0;
}
