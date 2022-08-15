#include "util/random.hpp"
#include "util/assembly.hpp"

static uint64_t lsfr = 0x01203891;

void init_seed(uint64_t seed) {
  lsfr = seed;
}

uint64_t random_fast() {
  uint64_t b63 = 0x1 & (lsfr >> 62);
  uint64_t b62 = 0x1 & (lsfr >> 61);
  lsfr = ((lsfr << 2) >> 1) | (b63 ^ b62);
  return lsfr;
}

void randomize_seed() {
  init_seed(rdtscfence());
}
