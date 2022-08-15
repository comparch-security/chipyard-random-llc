#include "cache/list.hpp"
#include "util/random.hpp"
#include "util/assembly.hpp"

#include <cstdlib>
#include <cstdint>
#include <set>
#include <cstdio>
#include <cassert>

void traverse_list_1(elem_t *ptr) {
  while(ptr) {
    maccess(ptr);
    ptr = ptr->next;
  }
}

void traverse_list_2(elem_t *ptr) {
  while(ptr) {
    maccess(ptr);
    maccess(ptr);
    ptr = ptr->next;
  }
}

void traverse_list_3(elem_t *ptr) {
  while(ptr) {
    maccess(ptr);
    maccess(ptr);
    maccess(ptr);
    ptr = ptr->next;
  }
}

void traverse_list_4(elem_t *ptr) {
  while(ptr && ptr->next && ptr->next->next) {
    maccess(ptr);
    maccess(ptr->next);
    maccess(ptr->next->next);
    maccess(ptr);
    maccess(ptr->next);
    maccess(ptr->next->next);
    maccess(ptr);
    maccess(ptr->next);
    maccess(ptr->next->next);
    maccess(ptr);
    maccess(ptr->next);
    maccess(ptr->next->next);
    ptr = ptr->next;
  }
  if(ptr && ptr->next) {
    maccess(ptr);
    maccess(ptr->next);
    maccess(ptr);
    maccess(ptr->next);
    maccess(ptr);
    maccess(ptr->next);
    maccess(ptr);
    maccess(ptr->next);
    ptr = ptr->next;
  }
  if(ptr) {
    maccess(ptr);
    maccess(ptr);
    maccess(ptr);
    maccess(ptr);
    ptr = ptr->next;
  }
}


void traverse_list_rr(elem_t *ptr) {
  while(ptr->next) {
    maccess(ptr);
    ptr = ptr->next;
  }
  while(ptr->prev) {
    maccess(ptr);
    ptr = ptr->prev;
  }
}

void traverse_list_ran(elem_t *ptr) {
  const int vs = 16;
  elem_t *vec[vs] = {NULL};
  uint64_t time, delay;
  while(ptr) {
    vec[random_fast() % vs] = ptr;
    ptr = ptr->next;
    for(int i=0; i<vs; i++) {
      if(vec[i]) {
        do {
          time = rdtscfence();
          maccess_fence (vec[i]);
          delay = rdtscfence() - time;
        } while (delay > CFG.flush_low);
      }
    }
  }
}

void traverse_list_param(elem_t *ptr, int repeat, int dis, int step) {
  int c=0, d=0, i=0;
  elem_t *start = ptr, *next = NULL;
  do {
    ptr = start;
    while(c<repeat) {
      c++;
      while(d<dis) {
        if(d == step) next = ptr;
        if(ptr) { maccess_fence(ptr); ptr = ptr->next; }
        d++;
      }
      d = 0;
      ptr = start;
    }
    c = 0;
    start = next;
    i += step;
  } while(start != NULL);
}

traverse_func choose_traverse_func(int t) {
  switch(t) {
  case 1:  return traverse_list_1;
  case 2:  return traverse_list_2;
  case 3:  return traverse_list_3;
  default: return traverse_list_4;
  }
}

int list_size(elem_t *ptr) {
  int rv = 0;
  while(ptr) { rv++; ptr = ptr->next; }
  return rv;
}

elem_t *pick_from_list(elem_t **pptr, int pksz) {
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

elem_t *append_list(elem_t *lptr, elem_t *rptr) {
  if(lptr == NULL) return rptr;
  if(rptr != NULL) {
    assert(rptr->prev == NULL);
    rptr->prev = lptr->tail;
    lptr->tail->next = rptr;
    lptr->ltsz += rptr->ltsz;
    lptr->tail = rptr->tail;
  }
  //printf("append into list size: %d-%d <- %d\n", lptr->ltsz, list_size(lptr), rptr->ltsz);
  return lptr;
}

std::vector<elem_t *> split_list(elem_t *ptr, int way) {
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
    //printf("%d ", rv[i]->ltsz);
    ltp[i]->next = NULL;
    rv[i]->tail = ltp[i];
  }
  //printf("\n");
  return rv;
}

elem_t *combine_lists(std::vector<elem_t *>lists) {
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
  //printf("combine into list size: %d\n", ltsz);
  return rv;
}

void print_list(elem_t *ptr) {
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
