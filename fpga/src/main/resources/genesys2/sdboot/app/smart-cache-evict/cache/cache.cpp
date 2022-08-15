#include "cache/cache.hpp"
#include "cache/list.hpp"
#include "util/assembly.hpp"

#include <cassert>
#include <cstdio>
#include <thread>
#include <atomic>
#include <mutex>

//#define SCE_CACHE_CALIBRATE_HISTO

#ifdef SCE_CACHE_CALIBRATE_HISTO
  #include "util/statistics.hpp"
  #include <iomanip>
  #include <fstream>
#endif

void calibrate(elem_t *victim) {
  float unflushed = 0.0;
  float flushed = 0.0;

#ifdef SCE_CACHE_CALIBRATE_HISTO
  uint32_t stat_histo_unflushed = init_histo_stat(20, CFG.calibrate_repeat);
  uint32_t stat_histo_flushed = init_histo_stat(40, CFG.calibrate_repeat);
#endif
  
  maccess (victim);
  maccess (victim);
  maccess (victim);
  maccess_fence (victim);

  for (int i=0; i<CFG.calibrate_repeat; i++) {
    maccess (victim);
    maccess (victim);
    maccess (victim);
    maccess (victim);

    uint64_t time = rdtscfence();
    maccess_fence (victim);
    uint64_t delta = rdtscfence() - time;
    unflushed += delta;

#ifdef SCE_CACHE_CALIBRATE_HISTO
    record_histo_stat(stat_histo_unflushed, (float)(delta));
#endif
  }
  unflushed /= CFG.calibrate_repeat;

  for (int i=0; i<CFG.calibrate_repeat; i++) {
    maccess (victim);
    maccess (victim);
    maccess (victim);
    maccess_fence (victim);

    flush (victim);
    uint64_t time = rdtscfence();
    maccess_fence (victim);
    uint64_t delta = rdtscfence() - time;
    flushed += delta;

#ifdef SCE_CACHE_CALIBRATE_HISTO
    record_histo_stat(stat_histo_flushed, (float)(delta));
#endif
  }
  flushed /= CFG.calibrate_repeat;

#ifdef SCE_CACHE_CALIBRATE_HISTO
  {
    std::ofstream outfile("unflushed.data", std::ofstream::app);
    auto hist = get_histo_density(stat_histo_unflushed);
    outfile << "=================" << std::endl;
    for(int i=0; i<hist.size(); i++)
      outfile << hist[i].first << "\t0\t0\t" << hist[i].second << "\t0" << std::endl;
    outfile.close();
  }

  {
    std::ofstream outfile("flushed.data", std::ofstream::app);
    auto hist = get_histo_density(stat_histo_flushed);
    outfile << "=================" << std::endl;
    for(int i=0; i<hist.size(); i++)
      outfile << hist[i].first << "\t0\t0\t" << hist[i].second << "\t0" << std::endl;
    outfile.close();
  }
#endif

  assert(flushed > unflushed);
  CFG.flush_low = (int)((2.0*flushed + 1.5*unflushed) / 3.5);
  CFG.flush_high  = (int)(flushed * 1.5);
  //printf("calibrate: (%f, %f) -> [%d : %d]\n", flushed, unflushed, CFG.flush_high, CFG.flush_low);

#ifdef SCE_CACHE_CALIBRATE_HISTO
  {
    std::ofstream outfile("flushed.data", std::ofstream::app);
    outfile << "[" << CFG.flush_low <<"," << CFG.flush_high << "]" << std::endl;
    outfile.close();
  }
#endif

}

bool test_tar(elem_t *ptr, elem_t *victim) {
  float latency = 0.0;
  int i=0, t=0;

  while(i<CFG.trials && t<CFG.trials*16) {
	//maccess_write (&(victim->next), i);
	//maccess_fence (victim);
	//maccess_write (&(victim->next), NULL);
	maccess (victim);
    maccess (victim);
	maccess (victim);
    maccess_fence (victim);

	for(int j=0; j<CFG.scans; j++) {
      //traverse_list_param(ptr, 2, 2, 1);
      traverse_list_rr(ptr);
    }

    if((char *)victim > CFG.pool_root + 2*CFG.elem_size)
      maccess_fence((char *)victim - 2*CFG.elem_size );

    if((char *)victim < CFG.pool_roof - 2*CFG.elem_size)
      maccess_fence((char *)victim + 2*CFG.elem_size);

	uint64_t delay = rdtscfence();
	maccess_fence (victim);
	delay = rdtscfence() - delay;
    //printf("%ld ", delay);
    if(delay < CFG.flush_high) {
      latency += (float)(delay);
      i++;
    }
    t++;
  }

  //printf("\n");

  if(i == CFG.trials) {
    latency /= i;
    return latency > (float)CFG.flush_low;
  } else {
    return false;
  }
}

#define NTD 7
std::atomic<elem_t *> thread_target;
std::atomic<int> tasks;
std::atomic<int> done;
std::atomic<bool> verify;
std::mutex mtx;

void traverse_thread() {
  bool has_work = false;
  std::unique_lock<std::mutex> lck (mtx,std::defer_lock);
  while(true) {
    lck.lock();
    if(tasks > 0) { tasks--; has_work = true;}
    else has_work = false;
    lck.unlock();

    if(has_work){
      if(verify)
        traverse_list_ran(thread_target);
      else
        traverse_list_1(thread_target);
      done++;
    }
  }
}

void init_threads() {
  tasks = 0;
  done = 0;
  thread_target = NULL;
  for(int i=0; i<CFG.scans; i++) {
    std::thread t(traverse_thread);
    t.detach();
  }
}

bool test_tar_pthread(elem_t *ptr, elem_t *victim, bool v) {
  float latency = 0.0;
  int i=0, t=0;

  verify = v;
  while(i<CFG.trials && t<CFG.trials*16) {

	uint64_t delay;
    do {
      thread_target = victim;
      tasks = CFG.scans;
      while(tasks != 0 && done != CFG.scans) {
        int t = tasks, d = done;
        maccess (victim);
        maccess (victim);
        maccess (victim);
        maccess_fence (victim);
      }
      done = 0;
      delay = rdtscfence();
      maccess_fence (victim);
      delay = rdtscfence() - delay;
    } while(delay > CFG.flush_low / 2);

    thread_target = ptr;
    int ntasks = CFG.scans;
    tasks = v ? 7 : ntasks;
    while(tasks != 0 && done != ntasks) {
      thread_target = ptr;
    }
    done = 0;

    delay = rdtscfence();
	maccess_fence (victim);
	delay = rdtscfence() - delay;
    //printf("%ld ", delay);
    if(delay < CFG.flush_high) {
      latency += (float)(delay);
      i++;
    }
    t++;
  }

  //printf("\n");

  if(i == CFG.trials) {
    latency /= i;
    return latency > (float)CFG.flush_low;
  } else {
    return false;
  }
}

bool test_tar_lists(std::vector<elem_t *> &lists, elem_t *victim, int skip) {
  float latency = 0.0;
  int i=0, t=0;

  while(i<CFG.trials && t<CFG.trials*16) {
	maccess (victim);
	maccess (victim);
	maccess (victim);
	maccess_fence (victim);

	for(int j=0; j<CFG.scans; j++)
      for(int k=0; k<lists.size(); k++)
        if(k!=skip) CFG.traverse(lists[k]);

    if((char *)victim > CFG.pool_root + 2*CFG.elem_size)
      maccess_fence((char *)victim - 2*CFG.elem_size );

    if((char *)victim < CFG.pool_roof - 2*CFG.elem_size)
      maccess_fence((char *)victim + 2*CFG.elem_size);

	uint64_t delay = rdtscfence();
	maccess_fence (victim);
	delay = rdtscfence() - delay;
    if(delay < CFG.flush_high) {
      latency += (float)(delay);
      i++;
    }
    t++;
  }

  if(i == CFG.trials) {
    latency /= i;
    return latency > (float)CFG.flush_low;
  } else {
    return false;
  }
}

bool test_arb(elem_t *ptr) {
  int count = 0;
  for(int i=0; i<CFG.trials; i++) {
	for(int j=0; j<CFG.scans; j++)
      CFG.traverse(ptr);

    elem_t *p = ptr;
    while(p) {
      uint64_t time = rdtscfence();
      maccess_fence(p);
      if(rdtscfence() - time > CFG.flush_low)
        count++;
      p = p->next;
    }
  }
  return count / CFG.trials  > CFG.cache_way;
}

float evict_rate(int ltsz, int trial) {
  float rate = 0.0;
  for(int i=0; i<trial; i++) {
    elem_t *ev_list = allocate_list(ltsz);
    calibrate(ev_list);
    bool res = test_tar(ev_list->next, ev_list);
    if(res) rate += 1.0;
    free_list(ev_list);
  }
  return rate / trial;
}
