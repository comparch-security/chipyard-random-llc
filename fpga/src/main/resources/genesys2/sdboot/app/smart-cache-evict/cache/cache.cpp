#include "cache/cache.hpp"
#include "cache/list.hpp"
#include "util/assembly.hpp"

#include <cassert>
#include <cstdio>
#include <thread>
#include <atomic>
#include <mutex>

#include <unistd.h>


//#define SCE_CACHE_CALIBRATE_HISTO

#ifdef SCE_CACHE_CALIBRATE_HISTO
  #include "util/statistics.hpp"
  #include <iomanip>
  #include <fstream>
#endif

void calibrate(elem_t *victim) {
  float unflushed = 0.0;
  float flushed = 0.0;
  float l1fl2uf = 0.0;
  int l2hit=0, l2miss=0;
  void *victimphy = (void *)virt2phy(victim);

#ifdef SCE_CACHE_CALIBRATE_HISTO
  uint32_t stat_histo_unflushed = init_histo_stat(20, CFG.calibrate_repeat);
  uint32_t stat_histo_flushed = init_histo_stat(40, CFG.calibrate_repeat);
#endif

  sched_yield();
  for (int i=0; i<CFG.calibrate_repeat; i++) {
    maccess (victim);
    unflushed += maccess_time(victim);

    flush (victimphy);  flush (victimphy);
    flushed += maccess_time(victim);

    maccess (victim); maccess_fence (victim);
    flushl1 (victimphy); flushl1 (victimphy);
    l1fl2uf += maccess_time(victim);

    l2hit += maccess_check(victim, victimphy);
    flush (victimphy); flush (victimphy);
    if(maccess_check(victim, victimphy)==0) l2miss++;

#ifdef SCE_CACHE_CALIBRATE_HISTO
    record_histo_stat(stat_histo_flushed, (float)(delta));
#endif
  }
  unflushed /= CFG.calibrate_repeat;
  flushed   /= CFG.calibrate_repeat;
  l1fl2uf   /= CFG.calibrate_repeat;

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

  CFG.flush_low = (int)((2.0*flushed + 1.5*l1fl2uf) / 3.5);
  CFG.flush_high  = (int)(flushed * 1.5);

#ifdef SCE_CACHE_CALIBRATE_HISTO
  {
    std::ofstream outfile("flushed.data", std::ofstream::app);
    outfile << "[" << CFG.flush_low <<"," << CFG.flush_high << "]" << std::endl;
    outfile.close();
  }
#endif
  printf("calibrate_done l2hit %d l2miss %d flushed %d l1fl2uf %d unflushed %d CFG.flush_low %d CFG.flush_high %d\n",
         l2hit, l2miss, (int)flushed, (int)(l1fl2uf), (int)unflushed, CFG.flush_low, CFG.flush_high);
  assert(flushed > unflushed);
}

bool test_tar(elem_t *ptr, elem_t *victim) {
  float latency = 0.0;
  int i=0, t=0;
  void *victimphy = (void *)virt2phy(victim);

  sched_yield();
  while(i<CFG.trials && t<CFG.trials*16) {
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

	  uint64_t delay = maccess_time(victim);
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
      else {
        if(rand() & 0x00) traverse_list_1_r(thread_target);
        else              traverse_list_1  (thread_target);
      }
      done++;
    }
  }
}

void init_threads() {
  tasks = 0;
  done = 0;
  thread_target = NULL;
  for(int i=0; i<CFG.scans; i++) {
    /*std::thread t(traverse_thread);
    printf("traverse_thread ID %ld joinable %d\n", t.get_id(), t.joinable());
    t.detach();*/
    pthread_t pthID;
    int err = pthread_create(&pthID, NULL, (void* (*)(void *)) &traverse_thread, NULL);
    printf("traverse_thread_%d err %d pid %ld\n", i, err, pthID);
    if(err!=0) exit(0);
  }
}

bool test_tar_pthread(elem_t *ptr, elem_t *victim, bool v) {
  float latency = 0.0;
  int i=0, t=0;
  void *victimphy = (void *)virt2phy(victim);

  verify = v;
  while(i<CFG.trials && t<CFG.trials*16) {

	uint64_t delay;
    do {
      thread_target = victim;
      tasks = CFG.scans;
      while(tasks != 0 && done != CFG.scans) {
        maccess (victim);
        maccess (victim);
        maccess (victim);
        maccess_fence (victim);
      }
      done = 0;
      delay = maccess_time(victim);
    } while(delay > CFG.flush_low / 2);
    thread_target = ptr;
    int ntasks = CFG.scans;
    tasks = v ? 7 : ntasks;
    while(tasks != 0 && done != ntasks) {
      sched_yield();
      thread_target = ptr;
    }
    done = 0;

	  delay = maccess_time(victim);
    //delay = (CFG.flush_low+CFG.flush_high) >> 1;
    //if(maccess_check(victim, victimphy)) delay = CFG.flush_low >> 2;
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

	  uint64_t delay = maccess_time(victim);
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
      if(maccess_time(p) > CFG.flush_low)
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
    elem_t *victim  = allocate_list(1);
    calibrate(ev_list);
    bool res = test_tar(ev_list, victim);
    if(res) rate += 1.0;
    free_list(ev_list);
    free_list(victim);
  }
  return rate / trial;
}
