#ifndef SCE_LIST_HPP
#define SCE_LIST_HPP

#include "common/definitions.hpp"
#include <vector>

extern void traverse_list_1(elem_t *ptr);
extern void traverse_list_2(elem_t *ptr);
extern void traverse_list_3(elem_t *ptr);
extern void traverse_list_4(elem_t *ptr);
extern void traverse_list_rr(elem_t *ptr);
extern void traverse_list_ran(elem_t *ptr);
extern void traverse_list_param(elem_t *ptr, int repeat, int dist, int step);
typedef void (*traverse_func)(elem_t *);
extern traverse_func choose_traverse_func(int);

extern int list_size(elem_t *ptr);
extern elem_t *pick_from_list(elem_t **ptr, int pksz);
extern elem_t *append_list(elem_t *lptr, elem_t *rptr);
extern std::vector<elem_t *> split_list(elem_t *lptr, int way);
extern elem_t *combine_lists(std::vector<elem_t *>lists);
extern void print_list(elem_t *ptr);

#endif
