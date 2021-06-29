#include <stdint.h>
#include <platform.h>
#include "uart.h"

static volatile uint32_t *uart_base_ptr = (uint32_t *)(UART_CTRL_ADDR);

void uart_init(void) {
  *(uart_base_ptr +(UART_REG_TXCTRL>>2)) = UART_TXEN;
}


inline void uart_send(uint8_t data) {
	volatile uint32_t *tx = uart_base_ptr +(UART_REG_TXFIFO>>2);
#ifdef __riscv_atomic
	int32_t r;
	do {
		__asm__ __volatile__ (
			"amoor.w %0, %2, %1\n"
			: "=r" (r), "+A" (*tx)
			: "r" (data));
	} while (r < 0);
#else
	while ((int32_t)(*tx) < 0);
	*tx = c;
#endif
}


void uart_send_string(const char *str){
	char c;
	for (; (c = *str) != '\0'; str++)
		uart_send(c);
}

void uart_send_buf(const char *buf, const int32_t len){

}


uint8_t uart_recv(void){
	return 0;
}

