#include "cache/cache.hpp"
#include "cache/list.hpp"

#include <cstdio>
#include <cstdint>
#include <thread>
#include <atomic>
#include <mutex>
#include <cassert>

void calibrate(elem_t *victim) {
  float unflushed = 0.0;
  float flushed = 0.0;
  uint64_t delta;

  for (int i=0; i<CFG.calibrate_repeat; i++) {
    maccess (victim);
    maccess (victim);
    maccess (victim);
    maccess_fence (victim);

    delta = rdtscfence();
    maccess_fence (victim);
    delta = rdtscfence() - delta;
    unflushed += delta;
  }
  unflushed /= CFG.calibrate_repeat;

  for (int i=0; i<CFG.calibrate_repeat; i++) {
    maccess (victim);
    maccess (victim);
    maccess (victim);
    maccess_fence (victim);

    flush (victim);
    delta = rdtscfence();
    maccess_fence (victim);
    delta = rdtscfence() - delta;
    flushed += delta;

  }
  flushed /= CFG.calibrate_repeat;

  CFG.flush_low = (int)((2.0*flushed + 1.5*unflushed) / 3.5);
  CFG.flush_high  = (int)(flushed * 1.5);
  //printf("calibrate: (%f, %f) -> [%d : %d]\n", flushed, unflushed, CFG.flush_high, CFG.flush_low);
}

bool test_tar(elem_t *ptr, elem_t *victim) {
  float latency = 0.0;
  int i=0, t=0;
  uint64_t delay;

  while(i<CFG.trials && t<CFG.trials*16) {
	maccess (victim);
    maccess (victim);
	maccess (victim);
    maccess_fence (victim);

	for(int j=0; j<CFG.scans; j++) {
      traverse_list(ptr, 2, 4);
      //traverse_list_rr(ptr, 1);
      //traverse_list_ran(ptr, 4);
      //traverse_list_param(ptr, 2, 2, 1);
    }

    maccess_fence((char *)((uint64_t)(victim) ^ 0x00000100ull));

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

std::atomic<elem_t *> thread_target;
std::atomic<int> tasks;
std::atomic<int> done;
std::mutex mtx_task, mtx_done;

void traverse_thread() {
  bool has_work = false;
  std::unique_lock<std::mutex> lck_task (mtx_task,std::defer_lock);
  std::unique_lock<std::mutex> lck_done (mtx_done,std::defer_lock);
  while(true) {
    lck_task.lock();
    if(tasks > 0) {
      tasks--;
      has_work = true;
    } else has_work = false;
    lck_task.unlock();

    elem_t *ptr = thread_target;
    if(has_work){
      //printf("%016lx: thread begin (%016lx)\n", &has_work, ptr);
      //traverse_list(thread_target, 2, 4);
      //traverse_list_rr(thread_target, 4);
      //traverse_list_ran(thread_target, 2);
      traverse_list_param(thread_target, 2, 2, 1);
      lck_done.lock();
      done++;
      lck_done.unlock();
      //printf("%016lx: thread done (%016lx)\n", &has_work, ptr);
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

bool test_tar_pthread(elem_t *ptr, elem_t *victim) {
  float latency = 0.0;
  int i=0, t=0;
  uint64_t delay;
  elem_t *victim_neighbour = (elem_t *)((uint64_t)(victim) ^ 0x00000100ull);
  std::unique_lock<std::mutex> lck_task (mtx_task,std::defer_lock);
  
  while(i<CFG.trials && t<CFG.trials*16) {
    do {
      thread_target = victim;
      done = 0;
      lck_task.lock();
      tasks = CFG.scans;
      lck_task.unlock();
      do {
        maccess (victim);
        maccess (victim);
        maccess (victim);
        maccess_fence (victim);
      } while(tasks != 0 || done != CFG.scans);
      delay = rdtscfence();
      maccess_fence (victim);
      delay = rdtscfence() - delay;
    } while(delay > CFG.flush_low);

    thread_target = ptr;
    assert(tasks == 0 && done == CFG.scans);
    done = 0;
    lck_task.lock();
    tasks = CFG.scans;
    lck_task.unlock();
    while(tasks != 0 || done != CFG.scans);

    do {
      delay = rdtscfence();
      maccess_fence(victim_neighbour);
      delay = rdtscfence() - delay;
    } while (delay > CFG.flush_low);

    delay = rdtscfence();
	maccess_fence (victim);
	delay = rdtscfence() - delay;
    //printf("%ld ", delay); fflush(stdout);
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
        if(k!=skip) {
          traverse_list(lists[k], 2, 4);
          //traverse_list_rr(lists[k], 4);
          //traverse_list_ran(lists[k], 4);
          //traverse_list_param(lists[k], 2, 2, 1);
        }

    maccess_fence((char *)((uint64_t)(victim) ^ 0x00000100ull));

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

float evict_rate(int ltsz, int trial) {
  float rate = 0.0;
  for(int i=0; i<trial; i++) {
    elem_t *ev_list = allocate_list(ltsz);
    elem_t *victim = allocate_list(1);
    calibrate(victim);
    bool res = test_tar_pthread(ev_list, victim);
    if(res && test_tar_pthread(ev_list, victim))
      rate += 1.0;
    free_list(ev_list);
    free_list(victim);
    //printf("."); fflush(stdout);
  }
  //printf("\n"); fflush(stdout);
  return rate / trial;
}
