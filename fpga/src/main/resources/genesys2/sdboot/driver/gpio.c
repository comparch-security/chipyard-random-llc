#include <stdint.h>
#include <platform.h>
#include "gpio.h"

inline uint64_t get_sw(void) {
  //uint64_t *addr = *(uint64_t *)(GPIO_CTRL_ADDR + GPIO_IOD_SW);
  return *((uint64_t *)(GPIO_CTRL_ADDR + GPIO_IOD_SW));
}
inline uint64_t get_but(void) {
  //uint64_t *addr = *(uint64_t *)(GPIO_CTRL_ADDR + GPIO_IOD_BUT);
  return *((uint64_t *)(GPIO_CTRL_ADDR + GPIO_IOD_BUT));
}

inline void set_led(uint64_t d) {
  uint64_t *addr = (uint64_t *)(GPIO_CTRL_ADDR + GPIO_IOD_LED);
  *addr = d;
}

inline void set_oled(uint64_t d) {
  uint64_t *addr = (uint64_t *)(GPIO_CTRL_ADDR + GPIO_IOD_OLED);
  *addr = d;
}
