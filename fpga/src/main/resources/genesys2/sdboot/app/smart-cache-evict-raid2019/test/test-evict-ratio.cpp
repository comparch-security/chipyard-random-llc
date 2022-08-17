#include "common/definitions.hpp"
#include "cache/cache.hpp"
#include "cache/list.hpp"
#include <cstdio>

int main() {
  init_cfg();
  randomize_seed();
  init_threads();
  for(int i=1000; i<500000; i*=1.1)
    printf("%i\t%f\n", i, evict_rate(i, 200)); fflush(stdout);
  return 0;
}
