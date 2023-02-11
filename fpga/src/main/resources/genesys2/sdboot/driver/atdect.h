#include "platform.h"


#define ATDECT_CONFIG0_ADDR         L2_CTRL_ADDR+L2_ATDECT_CONFIG0
#define ATDECT_CONFIG1_ADDR         L2_CTRL_ADDR+L2_ATDECT_CONFIG1

void  atdect_config0(uint32_t ath, uint8_t enath, uint32_t eth, uint8_t eneth);
void  atdect_config1(uint8_t  discount, uint32_t period, uint8_t zth, uint8_t enzth);

uint32_t   ath         ; 
uint8_t    enath       ;
uint32_t   eth         ;
uint8_t    eneth       ;
uint32_t   period      ;
uint8_t    zth         ;
uint8_t    discount    ;
uint8_t    enzth       ;

inline void atdect_config0(uint32_t ath, uint8_t enath, uint32_t eth, uint8_t eneth)
{ 
  uint64_t atdet_config0 = (uint64_t)((((uint64_t)ath << 1) + (enath & 0x01)) << 32) + (uint32_t)((eth << 1) + (eneth & 0x01));
  *(volatile uint64_t *)(ATDECT_CONFIG0_ADDR) = atdet_config0;
}

inline void atdect_config1(uint8_t discount, uint32_t period, uint8_t zth, uint8_t enzth)
{ 

   uint64_t atdet_config1 = (((uint64_t)discount) << 25)  + (((uint64_t)zth) << 21) + (((uint64_t)period) << 1) + (enzth & 0x01);

  *(volatile uint64_t *)(ATDECT_CONFIG1_ADDR) = atdet_config1;
}







