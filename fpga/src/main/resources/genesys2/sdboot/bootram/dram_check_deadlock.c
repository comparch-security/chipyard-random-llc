// See LICENSE for license details.

#include "uart.h"
#include "kprintf.h"
#include "platform.h"
#include "gpio.h"

#define STEP_SIZE 65536
#define MEM_END  (MEMORY_MEM_ADDR + MEMORY_MEM_SIZE)
 
#define test(base_addr)   asm volatile("sd zero,     0(%0)": : "r"(base_addr) :); \
                          asm volatile("sd zero,   128(%0)": : "r"(base_addr) :); \
                          asm volatile("sd zero,   256(%0)": : "r"(base_addr) :); \
                          asm volatile("sd zero,   384(%0)": : "r"(base_addr) :); \
                          asm volatile("sd zero,   512(%0)": : "r"(base_addr) :); \
                          asm volatile("sd zero,   640(%0)": : "r"(base_addr) :); \
                          asm volatile("sd zero,   768(%0)": : "r"(base_addr) :); \
                          asm volatile("sd zero,   896(%0)": : "r"(base_addr) :); \
                          asm volatile("sd zero,  1152(%0)": : "r"(base_addr) :); \
                          asm volatile("sd zero,  1280(%0)": : "r"(base_addr) :); \
                          asm volatile("sd zero,  1408(%0)": : "r"(base_addr) :); \
                          asm volatile("sd zero,  1536(%0)": : "r"(base_addr) :); \
                          asm volatile("sd zero,  1664(%0)": : "r"(base_addr) :); \
                          asm volatile("sd zero,  1792(%0)": : "r"(base_addr) :); \
                          asm volatile("sd zero,  1920(%0)": : "r"(base_addr) :); \
                          asm volatile("sd zero, -2048(%0)": : "r"(base_addr) :); \
                          asm volatile("sd zero, -1920(%0)": : "r"(base_addr) :); \
                          asm volatile("sd zero, -1792(%0)": : "r"(base_addr) :); \
                          asm volatile("sd zero, -1664(%0)": : "r"(base_addr) :); \
                          asm volatile("sd zero, -1536(%0)": : "r"(base_addr) :); \
                          asm volatile("sd zero, -1408(%0)": : "r"(base_addr) :); \
                          asm volatile("sd zero, -1280(%0)": : "r"(base_addr) :); \
                          asm volatile("sd zero, -1152(%0)": : "r"(base_addr) :); \
                          asm volatile("sd zero, - 896(%0)": : "r"(base_addr) :); \
                          asm volatile("sd zero, - 768(%0)": : "r"(base_addr) :); \
                          asm volatile("sd zero, - 640(%0)": : "r"(base_addr) :); \
                          asm volatile("sd zero, - 512(%0)": : "r"(base_addr) :); \
                          asm volatile("sd zero, - 384(%0)": : "r"(base_addr) :); \
                          asm volatile("sd zero, - 256(%0)": : "r"(base_addr) :); \
                          asm volatile("sd zero, - 128(%0)": : "r"(base_addr) :);


static uint64_t lfsr = 3;

inline uint64_t lfsr64(uint64_t d) {
  // x^64 + x^63 + x^61 + x^60 + 1
  return (d >> 1) | (((d >> (64-64)) ^
                      (d >> (64-63)) ^
                      (d >> (64-61)) ^
                      (d >> (64-60)) ^ 1) << 63);
}

int main(void) {
  register volatile uint64_t* addr_w0;
  register volatile uint64_t* addr_w1;
  register volatile uint64_t* addr_w2;
  register volatile uint64_t* addr_w3;
  register volatile uint64_t* addr_w4;
  register volatile uint64_t* addr_w5;
  #define test_all  test(addr_w0) \
                    test(addr_w1) \
                    test(addr_w2) \
                    test(addr_w3) \
                    test(addr_w4) \
                    test(addr_w5) 

  uart_init();
  kprintln("DRAM check deadlock.");
  uint64_t pc, sp, s0;
  asm volatile ("auipc %0,  0":  "=r" ((uint64_t)pc));
  asm volatile ("mv    %0, sp":  "=r" ((uint64_t)sp));
  asm volatile ("mv    %0, s0":  "=r" ((uint64_t)s0));
  kprintln("REG: pc      %x sp       %x s0    %x", pc, sp, s0);
  addr_w0 =  (uint64_t *)(MEMORY_MEM_ADDR+2048);
  while(1) {
    lfsr = lfsr64(lfsr); addr_w1 = addr_w0 + (lfsr & (STEP_SIZE - 1));
    lfsr = lfsr64(lfsr); addr_w2 = addr_w1 + (lfsr & (STEP_SIZE - 1));
    lfsr = lfsr64(lfsr); addr_w3 = addr_w2 + (lfsr & (STEP_SIZE - 1));
    lfsr = lfsr64(lfsr); addr_w4 = addr_w3 + (lfsr & (STEP_SIZE - 1));
    lfsr = lfsr64(lfsr); addr_w5 = addr_w4 + (lfsr & (STEP_SIZE - 1));
    if((uint64_t)addr_w1+2048 >= MEM_END) { addr_w1 = (uint64_t *)MEM_END-2048; }
    if((uint64_t)addr_w2+2048 >= MEM_END) { addr_w2 = (uint64_t *)MEM_END-2048; }
    if((uint64_t)addr_w3+2048 >= MEM_END) { addr_w3 = (uint64_t *)MEM_END-2048; }
    if((uint64_t)addr_w4+2048 >= MEM_END) { addr_w4 = (uint64_t *)MEM_END-2048; }
    if((uint64_t)addr_w5+2048 >= MEM_END) { addr_w5 = (uint64_t *)MEM_END-2048; }

    test_all;
    test_all;
    test_all;
    test_all;
    test_all;
    test_all;
    test_all;
    test_all;
    test_all;
    test_all;

    addr_w0  = addr_w0+(0x1ff & (lfsr++));
    if((uint64_t)addr_w0+2048 >= MEM_END) { 
      kprintln("pass"); 
      asm volatile ("mv a0, %0": :"r" ((uint64_t)(RESET_ADDR)) : "a0");
      asm volatile ("jr a0");
     __builtin_unreachable(); 
    }
    if((lfsr & 0xff) == 0) kprintln("@%x", addr_w0);
  } //while(1)
   __builtin_unreachable();
}
