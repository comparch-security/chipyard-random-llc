#pragma once

void     clflush            (void *p);
void     clflush_f          (void *p);
void     clflushl1          (void *p);
uint8_t  clcheck            (void *p);
uint8_t  maccess_check      (void* p, void* phyadr);

uint64_t rdcycle            (void);
uint64_t rdcycle            (void);

void     maccess            (void *p);
void     mwrite             (void *v);
int      mread              (void *v);
int      time_mread         (void *adrs);
int      time_mread_nofence (void *adrs);

#define  flush(x)            clflush_f(x)
#define  flush_nofence(x)    clflush(x)
#define  flushl1(x)          clflushl1(x)
#define  memwrite(x)         mwrite(x)
#define  memread(x)          mread(x)