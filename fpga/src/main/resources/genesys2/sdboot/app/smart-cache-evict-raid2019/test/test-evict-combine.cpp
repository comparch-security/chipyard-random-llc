#include "cache/cache.hpp"
#include "cache/algorithm.hpp"
#include "cache/list.hpp"
#include <cstdio>
#include <chrono>
#include <fstream>

int main() {
  init_cfg();
  randomize_seed();
  init_threads();
  int way = CFG.cache_way;
  int succ = 0, iter = 0, keep = 0;
  int csize = 224000;
  std::chrono::high_resolution_clock::time_point tb1, tb2, tend;
  long int time_all_acc = 0, time_trim_acc = 0;
  while (iter < 100) {
    elem_t *victim = allocate_list(1);
    calibrate(victim);
    int ct = 0;
    elem_t *candidate = NULL, *residue = NULL;
    bool rv = false;
    tb1 = std::chrono::high_resolution_clock::now();
    while(ct++ < 9) {
      if(candidate == NULL) {
        candidate = allocate_list(csize);
        while(!test_tar_pthread(candidate, victim)) {
          free_list(candidate);
          candidate = allocate_list(csize);
        }
      }
      tb2 = std::chrono::high_resolution_clock::now();
      rv = trim_tar_ran(&candidate, victim, way);
      tend = std::chrono::high_resolution_clock::now();
      if(rv) {
        rv = test_tar_pthread(candidate, victim);
        //printf("verify result %d way = %d\n", rv, way);
      } else {
        if(residue == NULL) {
          residue = candidate;
          candidate = NULL;
        } else {
          candidate = append_list(candidate, residue);
          residue = NULL;
        }
      }
      if(rv) break;
    } 
    if(candidate) free_list(candidate);
    if(residue) free_list(residue);
    free_list(victim);
    succ += rv;
    iter++;
    long int time_all = std::chrono::duration_cast<std::chrono::milliseconds>(tend - tb1).count();
    time_all_acc += time_all; 
    long int time_trim = std::chrono::duration_cast<std::chrono::milliseconds>(tend - tb2).count();
    time_trim_acc += time_trim;
    std::ofstream log("combine.log", std::ofstream::out | std::ofstream::app);
    log << "trials " << iter << " sucesses: " << succ << " result: " << rv << " (" << time_all << ")" << std::endl;
    //printf("trials %d sucesses: %d result: %d (%ld, %ld)\n", iter, succ, rv, time_all, time_trim);
    log.close();
  }
  float ratio = (float)succ / iter;
  time_all_acc /= iter;
  time_trim_acc /= iter;
  std::ofstream log("combine.log", std::ofstream::out | std::ofstream::app);
  log << "sucess ratio: " << ratio << " (" << time_all_acc << ")" << std::endl;
  //printf("sucess ratio: %f (%ld, %ld)\n", ratio, time_all_acc, time_trim_acc);
  log.close();
  return 0;
}
