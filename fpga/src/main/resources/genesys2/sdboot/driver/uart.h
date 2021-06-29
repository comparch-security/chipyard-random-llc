#include <platform.h>
#include <stdint.h>

void uart_init(void);

void uart_send(uint8_t data);
void uart_send_string(const char *str);
void uart_send_buf(const char *buf, const int32_t len);

uint8_t uart_recv(void);

