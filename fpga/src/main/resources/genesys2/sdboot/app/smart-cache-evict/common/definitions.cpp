#include "common/definitions.hpp"
#include "cache/list.hpp"
#include "database/json.hpp"
#include <fstream>
#include <sys/mman.h>
#include <fcntl.h>
#include <unistd.h>
#include "platform.h"
#include "util/assembly.hpp"

struct config CFG;
using json = nlohmann::json;
static json db;
static bool db_init = false;

#define CFG_SET_ENTRY(name, var, dvalue) \
  if(db.count(name)) var = db[name];     \
  else {                                 \
    var = dvalue;                        \
    db[name] = dvalue;                   \
  }                                      \

void init_cfg() {
  if(!db_init) {
    std::ifstream db_file("configure.json");
    if(!db_file.fail()) {
      db_file >> db;
      db_file.close();
    }
  }

  CFG_SET_ENTRY("candidate_size",   CFG.candidate_size,   0               )
  CFG_SET_ENTRY("cache_set",        CFG.cache_set,        1024            )
  CFG_SET_ENTRY("cache_way",        CFG.cache_way,        16              )
  CFG_SET_ENTRY("cache_size",       CFG.cache_size,       0               )
  CFG_SET_ENTRY("cache_slices",     CFG.cache_slices,     0               )
  CFG_SET_ENTRY("flush_low",        CFG.flush_low,        65              )
  CFG_SET_ENTRY("flush_high",       CFG.flush_high,       95              )
  CFG_SET_ENTRY("trials",           CFG.trials,           4               )
  CFG_SET_ENTRY("scans",            CFG.scans,            2               )
  CFG_SET_ENTRY("calibrate_repeat", CFG.calibrate_repeat, 1000            )
  CFG_SET_ENTRY("retry",            CFG.retry,            true            )
  CFG_SET_ENTRY("rtlimit",          CFG.rtlimit,          64              )
  CFG_SET_ENTRY("rollback",         CFG.rollback,         true            )
  CFG_SET_ENTRY("rblimit",          CFG.rblimit,          16              )
  CFG_SET_ENTRY("ignoreslice",      CFG.ignoreslice,      true            )
  CFG_SET_ENTRY("findallcolors",    CFG.findallcolors,    false           )
  CFG_SET_ENTRY("findallcongruent", CFG.findallcongruent, false           )
  CFG_SET_ENTRY("verify",           CFG.verify,           true            )
  CFG_SET_ENTRY("elem_size",        CFG.elem_size,        SZ_CL           ) //SZ_PG
  CFG_SET_ENTRY("timelimit",        CFG.timelimit,        1000*1000*1000  )
  if(CFG.elem_size == SZ_CL)   CFG_SET_ENTRY("pool_size",        CFG.pool_size,        (1<<20)         )
  else                         CFG_SET_ENTRY("pool_size",        CFG.pool_size,        (1<<10)         )

  if(db.count("traverse")) {
    int t = db["traverse"];
    CFG.traverse = choose_traverse_func(t);
  } else {
    CFG.traverse = traverse_list_4;
    db["traverse"] = 4;
  }

  CFG.dev_mem_fd = open("/dev/mem", O_RDWR);
  if(CFG.dev_mem_fd < 0) { printf("open(/dev/mem) failed.\n"); exit(1); }
  CFG.l2ctrl_base = (char *)mmap((void*)L2_CTRL_ADDR,     L2_CTRL_SIZE,    PROT_READ | PROT_WRITE, 
                                 MAP_SHARED,              CFG.dev_mem_fd,  L2_CTRL_ADDR);
  if(CFG.l2ctrl_base == MAP_FAILED)  { printf("L2CTRL mmap_fail!\n"); exit(1); }

  CFG.self_pagemap_fd = open("/proc/self/pagemap", O_RDONLY);
  if(CFG.self_pagemap_fd < 0) { printf("open(/proc/self/pagemap) failed\n"); exit(1); }

  if(!db_init) free(CFG.pool_root);

  //CFG.pool_root = (char *)mmap((void*)DRAM_TEST_ADDR, CFG.pool_size * CFG.elem_size, PROT_READ|PROT_WRITE,
  //                               MAP_SHARED|MAP_HUGETLB, CFG.dev_mem_fd, DRAM_TEST_ADDR);
  //if(CFG.pool_root == MAP_FAILED) {
  //  CFG.pool_root = (char *)mmap((void*)DRAM_TEST_ADDR, CFG.pool_size * CFG.elem_size, PROT_READ|PROT_WRITE,
  //                               MAP_SHARED, CFG.dev_mem_fd, DRAM_TEST_ADDR);
  //}

  CFG.pool_root = (char *)mmap(NULL, CFG.pool_size * CFG.elem_size, PROT_READ|PROT_WRITE,
                                 MAP_PRIVATE|MAP_ANONYMOUS|MAP_HUGETLB, 0, 0);
  if(CFG.pool_root == MAP_FAILED) {
    CFG.pool_root = (char *)mmap(NULL, CFG.pool_size * CFG.elem_size, PROT_READ|PROT_WRITE,
                                 MAP_PRIVATE|MAP_ANONYMOUS, 0, 0);
  }

  if(CFG.pool_root == MAP_FAILED) {
    printf("Failed to allocate pool using normal pages neither!\n");
    exit(1);
  }
  CFG.pagesize =  getpagesize();
  CFG.pool_roof = CFG.pool_root + CFG.pool_size * CFG.elem_size;
  CFG.pool = (elem_t *)CFG.pool_root;
  elem_t *ptr = CFG.pool;
  ptr->ltsz = CFG.pool_size;
  ptr->prev = NULL;
  for(uint32_t i=1; i<CFG.pool_size; i++) {
    ptr->next = (elem_t *)((char *)ptr + CFG.elem_size);
    ptr->next->prev = ptr;
    ptr = ptr->next;
  }
  ptr->next = NULL;
  CFG.pool->tail = ptr;

  db_init = true;
}

void dump_cfg() {
  std::ofstream db_file("configure.json");
  if(!db_file.fail()) {
    db_file << db.dump(4);
    db_file.close();
  }
}

elem_t *allocate_list(int ltsz) {
  return pick_from_list(&CFG.pool, ltsz);
}

void free_list(elem_t *l) {
  CFG.pool = append_list(CFG.pool, l);
}

void* virt2phy(const void *virtaddr) {
  long long page;
  long long virt_pfn = (unsigned long)virtaddr / CFG.pagesize;
  if(lseek(CFG.self_pagemap_fd, sizeof(uint64_t) * virt_pfn, SEEK_SET) == -1) return (void *)-1;
  read(CFG.self_pagemap_fd, &page, 8);
  return (void *)(((page & 0x7fffffffffffffULL) * CFG.pagesize) + ((unsigned long)virtaddr % CFG.pagesize));
}

void close_cfg() {
  munmap(CFG.l2ctrl_base,   L2_CTRL_SIZE  );
  munmap(CFG.pool_root  ,   CFG.pool_size * CFG.elem_size);
  close(CFG.dev_mem_fd);
}