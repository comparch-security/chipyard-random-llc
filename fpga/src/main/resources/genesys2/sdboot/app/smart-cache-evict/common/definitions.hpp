#ifndef SCE_DEFINITIONS_HPP
#define SCE_DEFINITIONS_HPP

#define SZ_CL  64
#define SZ_PG  4096

typedef struct elem {
  struct elem *next;
  struct elem *prev;
  struct elem *tail;
  int ltsz;
} elem_t;

struct config {
  int candidate_size;               // number of candidate cache lines
  int cache_size;                   // size if the cache in bytes
  int cache_way;                    // number of ways
  int cache_slices;                 // number of LLC slices
  int flush_low;                    // the latency lower bound of considering as evicted
  int flush_high;                   // the latency higher bound of considering as evicted
  int trials;                       // number of trials for each iteration
  int scans;                        // number of scans for each trial
  int calibrate_repeat;             // repeat in the calibration process
  bool retry;
  int rtlimit;                      // limit of retry for a constant size
  bool rollback;
  int rblimit;                      // depth of rollbacks
  bool ignoreslice;
  bool findallcolors;
  bool findallcongruent;
  bool verify;
  void (*traverse)(elem_t *);       // list traver function
  int pool_size;                    // the size of the element pool
  int elem_size;                    // size of an element
  char *pool_root;                  // base memory address of the pool
  char *pool_roof;                  // max memory address of the pool
  elem *pool;                       // pointer of the pool
};

extern struct config CFG;
extern void init_cfg();
extern void dump_cfg();

extern elem_t *allocate_list(int ltsz);
extern void free_list(elem_t *l);
#endif
