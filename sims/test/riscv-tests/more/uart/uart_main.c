#include <stdint.h>

#define UART_TXEN               0x1
#define UART_REG_TXFIFO         0x00
#define UART_REG_TXCTRL         0x08
#define UART_REG_DIV            0x18
#define UART_CTRL_ADDR          0x64000000

static volatile uint32_t *uart_base_ptr = (uint32_t *)(UART_CTRL_ADDR);

void uart_init(void) {
  *(uart_base_ptr +(UART_REG_TXCTRL>>2)) = UART_TXEN;
  //*(uart_base_ptr +(UART_REG_DIV>>2))    = 0; //when simulation the UART divisor was fixed to zero
}


inline void uart_send(char data) {
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

int main (int argc, char** argv) {
  uart_init();
  uart_send_string("hello world!\n");
  return 0;
}
