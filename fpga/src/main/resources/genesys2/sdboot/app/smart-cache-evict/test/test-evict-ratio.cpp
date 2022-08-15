#include "common/definitions.hpp"
#include "cache/cache.hpp"
#include "util/random.hpp"
#include <cstdio>

int main() {
  init_cfg();
  randomize_seed();
  for(int i=10000; i<500000; i+=10000)
    printf("%i:\t%f\n", i, evict_rate(i, 40));
  return 0;
}
