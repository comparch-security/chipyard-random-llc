#include "common/definitions.hpp"
#include "cache/list.hpp"
#include "database/json.hpp"
#include <fstream>
#include <sys/mman.h>

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
  CFG_SET_ENTRY("cache_size",       CFG.cache_size,       0               )
  CFG_SET_ENTRY("cache_way",        CFG.cache_way,        0               )
  CFG_SET_ENTRY("cache_slices",     CFG.cache_slices,     0               )
  CFG_SET_ENTRY("flush_low",        CFG.flush_low,        0               )
  CFG_SET_ENTRY("flush_high",       CFG.flush_high,       0               )
  CFG_SET_ENTRY("trials",           CFG.trials,           4               )
  CFG_SET_ENTRY("scans",            CFG.scans,            3               )
  CFG_SET_ENTRY("calibrate_repeat", CFG.calibrate_repeat, 1000            )
  CFG_SET_ENTRY("retry",            CFG.retry,            true            )
  CFG_SET_ENTRY("rtlimit",          CFG.rtlimit,          64              )
  CFG_SET_ENTRY("rollback",         CFG.rollback,         true            )
  CFG_SET_ENTRY("rblimit",          CFG.rblimit,          16              )
  CFG_SET_ENTRY("ignoreslice",      CFG.ignoreslice,      true            )
  CFG_SET_ENTRY("findallcolors",    CFG.findallcolors,    false           )
  CFG_SET_ENTRY("findallcongruent", CFG.findallcongruent, false           )
  CFG_SET_ENTRY("verify",           CFG.verify,           true            )
  CFG_SET_ENTRY("pool_size",        CFG.pool_size,        (1<<18)         )
  CFG_SET_ENTRY("elem_size",        CFG.elem_size,        SZ_CL           )

  if(db.count("traverse")) {
    int t = db["traverse"];
    CFG.traverse = choose_traverse_func(t);
  } else {
    CFG.traverse = traverse_list_4;
    db["traverse"] = 4;
  }

  if(!db_init) free(CFG.pool_root);
  //CFG.pool_root = (char *)malloc(CFG.pool_size * CFG.elem_size);
  CFG.pool_root = (char *)mmap(NULL, CFG.pool_size * CFG.elem_size, PROT_READ|PROT_WRITE,
                               MAP_PRIVATE|MAP_ANONYMOUS|MAP_HUGETLB, 0, 0);
  if(CFG.pool_root == MAP_FAILED) {
    printf("Failed to allocate pool using huge pages. Use normal pages instead.\n");
    CFG.pool_root = (char *)mmap(NULL, CFG.pool_size * CFG.elem_size, PROT_READ|PROT_WRITE,
                                 MAP_PRIVATE|MAP_ANONYMOUS, 0, 0);
  }
  if(CFG.pool_root == MAP_FAILED) {
    printf("Failed to allocate pool using normal pages neither!\n");
    exit(1);
  }
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
