#include "cache/algorithm.hpp"
#include "cache/cache.hpp"
#include "cache/list.hpp"

#include <vector>
#include <cstdint>

#include <cassert>
#include <cstdio>
#include <cstdlib>

bool trim_tar_ran(elem_t **candidate, elem_t *victim, int &way) {
  std::vector<elem_t *> stack(CFG.rblimit, 0);
  int stack_read = 0, stack_write = 0;
  bool started = false;
  int retry = 0;
  int ltsz = (*candidate)->ltsz;
  int ltsz_min = ltsz;
  int iter = 0, max_iter = ltsz > 50000 ? 500 : (ltsz > 10000 ? 2000 : (ltsz > 1000 ? 20000 : 200000));
  int level = 0, rblevel = 0;
  while(ltsz > 4) {
    int step = ltsz > way ? ltsz / way : 1;
    iter++;
    stack[stack_write] = pick_from_list(candidate, step);
    if(test_tar_pthread(*candidate, victim, false)) {
      ltsz -= step;
      stack_write = (stack_write + 1) % CFG.rblimit;
      level++;
      if(stack_read == stack_write) {
        free_list(stack[stack_read]);
        stack_read = (stack_read + 1) % CFG.rblimit;
      }
      if(ltsz < ltsz_min) {
        //printf("%d (%d,%d,%d) %d\n", ltsz, level, iter, level-rblevel-1, retry);
        //max_iter += level*4;
        rblevel = level;
        iter = 0;
        ltsz_min = ltsz;
      }
      retry = 0;
    } else {
      *candidate = append_list(*candidate, stack[stack_write]);
      if(iter > max_iter) {
        printf("failed with iteration %d > %d! ltsz = %d\n", iter, max_iter, ltsz_min);
        break;
      } else if(CFG.retry && retry < CFG.rtlimit)
        retry++;
      else if(ltsz > way + 1 && CFG.rollback && stack_read != stack_write) {
        int max_rb = (stack_write < stack_read)
          ? stack_write + CFG.rblimit - stack_read
          : stack_write - stack_read;
        for(int r=0; r < 1 + max_rb/8; r++) {
          stack_write = (stack_write + CFG.rblimit - 1) % CFG.rblimit;
          level--;
          ltsz += stack[stack_write]->ltsz;
          *candidate = append_list(*candidate, stack[stack_write]);
        }
        if(rblevel > level) rblevel = level;
        retry = 0;
      } else {
        printf("finished with maximal rollback! ltsz = %d\n", ltsz_min);
        break;
      }
    }
  }

  // housekeeping
  while(CFG.rollback && stack_read != stack_write) {
    free_list(stack[stack_read]);
    stack_read = (stack_read + 1) % CFG.rblimit;
  }

  if(ltsz <= way + 1) {
    //printf("success with way %d\n", ltsz);
    //printf("targeted victim: 0x%016lx\n", (uint64_t)victim);
    //print_list(*candidate);
    if(way > ltsz)      way = (ltsz + way)/2;
    else if(way < ltsz) way = ltsz;
    return true;
  } else
    return false;
}

bool trim_tar_split(elem_t **candidate, elem_t *victim, int &way) {
  std::vector<elem_t *> stack(CFG.rblimit, 0);
  int stack_read = 0, stack_write = 0;
  bool started = false;
  int retry = 0;
  int ltsz = (*candidate)->ltsz;
  int ltsz_min = ltsz;
  int iter = 0, max_iter = way;
  int level = 0, rblevel = 0;
  while(true) {
    std::vector<elem_t *> lists = split_list(*candidate, way*2);
    int vsz = lists.size();
    int i;
    for(i=0; i<vsz; i++) {
      iter++;
      if(test_tar_lists(lists, victim, i)) {
        ltsz -= lists[i]->ltsz;
        stack[stack_write] = lists[i];
        lists[i] = NULL;
        stack_write = (stack_write + 1) % CFG.rblimit;
        level++;
        if(stack_read == stack_write) {
          free_list(stack[stack_read]);
          stack_read = (stack_read + 1) % CFG.rblimit;
        }
        if(ltsz < ltsz_min) {
          //printf("%d (%d,%d,%d) %d\n", ltsz, level, iter, level-rblevel-1, i);
          max_iter += level;
          rblevel = level;
          iter = 0;
          ltsz_min = ltsz;
        }
        break;
      }
    }
    *candidate = combine_lists(lists);
    if(i == vsz) {
      if(iter > max_iter) {
        //printf("failed with iteration %d > %d !\n", iter, max_iter);
        break;
      } else if(ltsz > way + 1 && CFG.rollback && stack_read != stack_write) {
        int max_rb = (stack_write < stack_read)
          ? stack_write + CFG.rblimit - stack_read
          : stack_write - stack_read;
        for(int r=0; r < 1 + (max_rb/4); r++) {
          stack_write = (stack_write + CFG.rblimit - 1) % CFG.rblimit;
          level--;
          ltsz += stack[stack_write]->ltsz;
          *candidate = append_list(*candidate, stack[stack_write]);
        }
        if(rblevel > level) rblevel = level;
      } else {
        break;
      }
    }
  }

  // housekeeping
  while(CFG.rollback && stack_read != stack_write) {
    free_list(stack[stack_read]);
    stack_read = (stack_read + 1) % CFG.rblimit;
  }

  if(ltsz <= way + 1) {
    //printf("targeted victim: 0x%016lx\n", (uint64_t)victim);
    //print_list(*candidate);
    if(way > ltsz)      way = (ltsz + way)/2;
    else if(way < ltsz) way = ltsz;
    return true;
  } else
    return false;
}

bool trim_tar_combined_ran(elem_t **candidate, elem_t *victim, int &way, int csize, int th, int tmax) {
  int tcnt = 0;
  elem_t *residue = NULL;
  bool rv = false;
  do {
    //assert(CFG.pool->ltsz == list_size(CFG.pool));
    //if(residue != NULL) assert(residue->ltsz == list_size(residue));
    //if(*candidate != NULL) assert((*candidate)->ltsz == list_size(*candidate));
    //printf("%d: pool size: %d, residue size: %d, candidate size: %d\n",
    //       tcnt,
    //       CFG.pool->ltsz,
    //       residue == NULL ? 0 : residue->ltsz,
    //       *candidate == NULL ? 0 : (*candidate)->ltsz);
    if(*candidate == NULL) {
      printf("find candidate set");
      do {
        *candidate = allocate_list(csize);
        if(!test_tar_pthread(*candidate, victim, false)) {
          free_list(*candidate);
          printf(".");
          *candidate = NULL;
        }
      } while(*candidate == NULL);
      printf("\n");
    }
    rv = trim_tar_ran(candidate, victim, way);
    if(!rv) {
      if(residue == NULL) {
        residue = *candidate;
        *candidate = NULL;
      } else {
        *candidate = append_list(*candidate, residue);
        residue = NULL;
      }
    }
    tcnt++;
  } while(!rv && tcnt < tmax);
  if(residue != NULL) free_list(residue);
  return rv;
}

int trim_tar_final(elem_t **candidate, elem_t *victim) {
  elem_t *stack = NULL, *pick;
  int retry = 0;
  int ltsz = (*candidate)->ltsz;
  while(true) {
    pick = pick_from_list(candidate, 1);
    //assert((*candidate)->ltsz == list_size(*candidate));
    //assert((*candidate)->ltsz == ltsz - 1);
    if(test_tar_pthread(*candidate, victim, true)) {
      ltsz--;
      if(stack) free_list(stack);
      stack = pick;
      retry = 0;
    } else {
      *candidate = append_list(*candidate, pick);
      //assert((*candidate)->ltsz == ltsz);
      //assert((*candidate)->ltsz == list_size(*candidate));
      if(CFG.retry && retry < CFG.rtlimit)
        retry++;
      else {
        //printf("finished with ltsz = %d\n", ltsz);
        //print_list(*candidate);
        break;
      }
    }
  }

  // housekeeping
  //printf("house keeping...\n");
  if(stack) free_list(stack);

  return ltsz;
}
