#include <stdint.h>
#include <platform.h>
#include "spi.h"


#define F_CLK TL_CLK
static volatile uint32_t * const spi_base_ptr = (void *)(SPI_CTRL_ADDR); //uint32_t

uint8_t spi_xfer(uint8_t d)
{
  int32_t r;
  *(spi_base_ptr +(SPI_REG_TXFIFO>>2)) = d;
  do {
  	r = *(spi_base_ptr +(SPI_REG_RXFIFO>>2));
  } while (r < 0);
  return (uint8_t)r;

}

void spi_init(void) {
  uint8_t i;
  *(spi_base_ptr +(SPI_REG_SCKDIV>>2)) = (F_CLK / 300000UL);
  *(spi_base_ptr +(SPI_REG_CSMODE>>2)) = SPI_CSMODE_OFF;
  for (i = 10; i > 0; i--) spi_xfer(0xff);
  *(spi_base_ptr +(SPI_REG_CSMODE>>2)) = SPI_CSMODE_AUTO;
  for (i = 200; i > 1; i--);
  *(spi_base_ptr +(SPI_REG_SCKDIV>>2)) = (F_CLK / 5000000UL);
  *(spi_base_ptr +(SPI_REG_CSMODE>>2)) = SPI_CSMODE_AUTO;
}

void spi_disable(void) {
   uint8_t i;
  *(spi_base_ptr +(SPI_REG_CSMODE>>2)) = SPI_CSMODE_OFF;
   for (i = 200; i > 1; i--);
}

uint8_t spi_send(uint8_t dat) {
  return spi_xfer(dat);
}

void spi_send_multi(const uint8_t* dat, uint8_t n) {
  uint8_t i;
  for(i=0; i<n; i++) spi_xfer(*(dat++));
}

void spi_recv_multi(uint8_t* dat, uint8_t n) {
  uint8_t i;
  for(i=0; i<n; i++) *(dat++) = spi_xfer(0xff);
}

void spi_select_slave(uint8_t id) {
  *(spi_base_ptr +(SPI_REG_CSMODE>>2)) = SPI_CSMODE_HOLD;
}

void spi_deselect_slave(uint8_t id) {
  *(spi_base_ptr +(SPI_REG_CSMODE>>2)) = SPI_CSMODE_OFF;
}




