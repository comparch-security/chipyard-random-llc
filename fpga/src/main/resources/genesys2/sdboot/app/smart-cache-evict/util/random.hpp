#ifndef SCE_RANDOM_HPP
#define SCE_RANDOM_HPP

#include <cstdint>

extern void init_seed(uint64_t seed);
extern uint64_t random_fast();
extern void randomize_seed();

#endif
