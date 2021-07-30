// See LICENSE for license details.

#include "gpio.h"
#include "platform.h"

int main() {
   uint64_t d;
   uint64_t sw;
   uint64_t but;
   while(1) {
     for(d=1000;d>1;d--);
     sw  = get_sw();
     but = get_but();
     set_led(sw+but);
   }
}
