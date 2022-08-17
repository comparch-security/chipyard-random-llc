#ifndef SCE_ALGORITHM_HPP
#define SCE_ALGORITHM_HPP

#include "common/definitions.hpp"

extern bool trim_tar_ran(elem_t **candidate, elem_t *victiom, int &way);
extern bool trim_tar_split(elem_t **candidate, elem_t *victiom, int &way);
extern bool trim_tar_combined_ran(elem_t **candidate, elem_t *victiom, int &way, int csize, int th, int tmax);
extern int  trim_tar_final(elem_t **candidate, elem_t *victim);
#endif
