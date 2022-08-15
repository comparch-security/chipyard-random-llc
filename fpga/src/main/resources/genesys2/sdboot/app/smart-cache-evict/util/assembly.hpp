#ifndef SCE_ASSEMBLY_HPP
#define SCE_ASSEMBLY_HPP

#include <cstdint>

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

#endif
