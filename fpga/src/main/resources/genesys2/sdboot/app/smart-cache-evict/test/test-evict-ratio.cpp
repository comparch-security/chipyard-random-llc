#include "common/definitions.hpp"
#include "cache/cache.hpp"
#include "util/random.hpp"
#include <cstdio>
#include <unistd.h>

int main() {
  printf("\rmain PID %d\n", getpid());
  init_cfg();
  randomize_seed();
  for(int i=10000; i<500000; i+=1000)
    printf("%i:\t%f\n", i, evict_rate(i, 40));
  close_cfg();
  return 0;
}
