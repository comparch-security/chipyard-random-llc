#ifndef SCE_CACHE_HPP
#define SCE_CACHE_HPP

#include "common/definitions.hpp"
#include <vector>

extern void calibrate(elem_t *ptr);

extern bool test_tar(elem_t *ptr, elem_t *victim);
extern bool test_tar_pthread(elem_t *ptr, elem_t *victim, bool verify);
extern bool test_tar_lists(std::vector<elem_t *> &lists, elem_t *victim, int skip);
extern bool test_arb(elem_t *ptr);
extern void init_threads();

extern float evict_rate(int ltsz, int trial);

#endif
