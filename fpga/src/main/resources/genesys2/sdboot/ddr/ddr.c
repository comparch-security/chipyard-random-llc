// See LICENSE for license details.

#include "uart.h"
#include "kprintf.h"
#include "platform.h"


int main() {

  uart_init();
  kputln("program run at DDR");
  kputln("while(1)");
  while(1);

}
