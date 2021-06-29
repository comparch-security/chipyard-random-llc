#include <platform.h>
#include <stdint.h>

void spi_init(void);

void spi_disable(void);
uint8_t spi_xfer(uint8_t d);
uint8_t spi_send(uint8_t dat);

void spi_send_multi(const uint8_t* dat, uint8_t n);

void spi_recv_multi(uint8_t* dat, uint8_t n);

// select slave device
void spi_select_slave(uint8_t id);

// deselect slave device
void spi_deselect_slave(uint8_t id);
