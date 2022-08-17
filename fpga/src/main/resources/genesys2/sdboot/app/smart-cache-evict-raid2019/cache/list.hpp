#ifndef SCE_LIST_HPP
#define SCE_LIST_HPP

#include "common/definitions.hpp"
#include <vector>
#include <set>

#include <cstdlib>
#include <cstdint>
#include <cstdio>

inline void flush(void *p) {
  __asm__ volatile ("clflush 0(%0)" : : "c" (p) : "rax");
}

inline uint64_t rdtscfence() {
  uint64_t a, d;
  __asm__ volatile ("lfence; rdtsc; lfence" : "=a" (a), "=d" (d) : :);
  return ((d<<32) | a);
}

inline void maccess(void* p) {
  __asm__ volatile ("movq (%0), %%rax\n" : : "c" (p) : "rax");
}

inline void maccess_write(void* p, long long a) {
  __asm__ volatile ("movq %1, (%0); sfence;" : : "c" (p), "r" (a) : "rax");
}

inline void maccess_fence(void* p) {
  __asm__ volatile ("movq (%0), %%rax\n; lfence;" : : "c" (p) : "rax");
}

static uint64_t lsfr = 0x01203891;

inline void init_seed(uint64_t seed) {
  lsfr = seed;
}

inline uint64_t random_fast() {
  uint64_t b63 = 0x1 & (lsfr >> 62);
  uint64_t b62 = 0x1 & (lsfr >> 61);
  lsfr = ((lsfr << 2) >> 1) | (b63 ^ b62);
  return lsfr;
}

inline void randomize_seed() {
  init_seed(rdtscfence());
}

inline void traverse_list(elem_t *ptr, int dep, int rep) {
  int i;
  if(dep >= 2) {
    while(ptr && ptr->next && ptr->next->next) {
      for(i=0; i<rep; i++) {
        maccess(ptr);
        maccess(ptr->next);
        maccess(ptr->next->next);
      }
      ptr = ptr->next;
    }
  }
  if(dep >= 1) {
    while(ptr && ptr->next) {
      for(i=0; i<rep; i++) {
        maccess(ptr);
        maccess(ptr->next);
      }
      ptr = ptr->next;
    }
  }
  while(ptr) {
    for(i=0; i<rep; i++) {
      maccess(ptr);
    }
    ptr = ptr->next;
  }
}

inline void traverse_list_rr(elem_t *ptr, int rep) {
  int i;
  while(ptr->next) {
    for(i=0; i<rep; i++)
      maccess(ptr);
    ptr = ptr->next;
  }
  for(i=0; i<rep; i++)
    maccess(ptr);
  while(ptr->prev) {
    for(i=0; i<rep; i++)
      maccess(ptr);
    ptr = ptr->prev;
  }
}

inline void traverse_list_ran(elem_t *ptr, int dep) {
  int vsz = 1 << dep;
  int mask = vsz - 1;
  int i;
  std::vector<elem_t *> vec(vsz, NULL);
  while(ptr) {
    vec[random_fast() & mask] = ptr;
    ptr = ptr->next;
    for(i=0; i<vsz; i++)
      if(vec[i]) maccess(vec[i]);
  }
}

inline void traverse_list_param(elem_t *ptr, int repeat, int dis, int step) {
  int c=0, d=0;
  elem_t *start = ptr, *next = NULL;
  do {
    ptr = start;
    c = repeat;
    while(c--) {
      while(d++<dis) {
        if(ptr) { maccess(ptr); ptr = ptr->next; }
        if(d == step) next = ptr;
      }
      d = 0;
      ptr = start;
    }
    start = next;
  } while(start != NULL);
}

inline int list_size(elem_t *ptr) {
  int rv = 0;
  while(ptr) { rv++; ptr = ptr->next; }
  return rv;
}

inline elem_t *pick_from_list(elem_t **pptr, int pksz) {
  int ltsz = (*pptr)->ltsz;
  std::set<int> pick_set;
  while(pick_set.size() < pksz) {
    pick_set.insert(random_fast() % ltsz);
  }

  int index = 0;
  elem_t *rv, *pick = NULL, *ptr = *pptr, *pend = NULL;
  while(ptr) {
    if(pick_set.count(index)) {
      elem_t *p = ptr;
      ptr = ptr->next;

      if(p->prev != NULL) p->prev->next = p->next; else *pptr = p->next;
      if(p->next != NULL) p->next->prev = p->prev;

      if(pick == NULL) rv = p;
      else             pick->next = p;
      p->prev = pick;
      p->next = NULL;
      pick = p;
    } else {
      pend = ptr;
      ptr = ptr->next;
    }
    index++;
  }
  rv->ltsz = pksz;
  rv->tail = pick;
  rv->prev = NULL;
  (*pptr)->ltsz = ltsz - pksz;
  (*pptr)->tail = pend;
  (*pptr)->prev = NULL;
  return rv;
}

inline elem_t *append_list(elem_t *lptr, elem_t *rptr) {
  if(lptr == NULL) return rptr;
  if(rptr != NULL) {
    rptr->prev = lptr->tail;
    lptr->tail->next = rptr;
    lptr->ltsz += rptr->ltsz;
    lptr->tail = rptr->tail;
  }
  return lptr;
}

inline std::vector<elem_t *> split_list(elem_t *ptr, int way) {
  int vsz = ptr->ltsz > way ? way : ptr->ltsz;
  std::vector<elem_t *> rv(vsz, NULL);
  std::vector<elem_t *> ltp(vsz, NULL);
  int index = 0;
  while(ptr) {
    if(rv[index] == NULL) {
      rv[index] = ptr;
      ltp[index] = ptr;
      ptr->prev = NULL;
      rv[index]->ltsz = 1;
    } else {
      rv[index]->ltsz++;
      ltp[index]->next = ptr;
      ptr->prev = ltp[index];
      ltp[index] = ptr;
    }
    index = (index + 1) % vsz;
    ptr = ptr->next;
  }
  for(int i=0; i<vsz; i++) {
    ltp[i]->next = NULL;
    rv[i]->tail = ltp[i];
  }
  return rv;
}

inline elem_t *combine_lists(std::vector<elem_t *>lists) {
  int vsz = lists.size();
  elem_t *rv = NULL, *ptr = NULL;
  int ltsz = 0;
  for(int i=0; i<vsz; i++) {
    if(lists[i] != NULL) {
      ltsz += lists[i]->ltsz;
      if(rv == NULL) {
        rv = lists[i];
      } else {
        ptr->next = lists[i];
        lists[i]->prev = ptr;
      }
      ptr = lists[i]->tail;
    }
  }
  rv->tail = ptr;
  rv->ltsz = ltsz;
  return rv;
}

inline void print_list(elem_t *ptr) {
  printf("List with %d elements:\n", ptr->ltsz);
  int i = 0;
  while(ptr) {
    printf("0x%016lx ", (uint64_t)ptr);
    ptr = ptr->next;
    i++;
    if(i==4) { printf("\n"); i=0;}
  }
  printf("\n");
}

inline elem_t *allocate_list(int ltsz) {
  return pick_from_list(&CFG.pool, ltsz);
}

inline void free_list(elem_t *l) {
  CFG.pool = append_list(CFG.pool, l);
}

#endif
