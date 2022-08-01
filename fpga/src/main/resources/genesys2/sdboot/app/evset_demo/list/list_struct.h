#pragma once

#include <stddef.h> // For size_t


typedef struct cacheBlock {
  uint8_t               hit;
  uint8_t               pad[7];
  struct cacheBlock*    start;
  struct cacheBlock*    prev;
  struct cacheBlock*    next;
  uint64_t              line[4];
}__attribute__((aligned(64))) cacheBlock_t;
