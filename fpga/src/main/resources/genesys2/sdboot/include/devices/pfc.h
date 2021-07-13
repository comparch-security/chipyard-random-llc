// See LICENSE.Sifive for license details.

#ifndef _PFC_H
#define _PFC_H

/* Register offsets */

#define PFC_CORES           1
#define PFC_L2BANKS         1
#define PFC_ISETS           64
#define PFC_DSETS           64
#define PFC_L2SETS          1024

#define PFC_C_ADDR             0x8e0
#define PFC_M_ADDR             0x8e1
#define PFC_R_ADDR             0xce0

#define PFC_C_TRIGGER          0x00
#define PFC_C_INTERRUPT        0x01
#define PFC_C_EMPTY            0x02
#define PFC_C_READERROR        0x03
#define PFC_C_TIMEOUT          0x04
#define PFC_C_RESEVER          0x05
#define PFC_C_PAGE             0x0c
#define PFC_C_RAM              0x13
#define PFC_C_MANAGERID        0x14

#define PFC_C_TRIGGER_BIT      (1 << PFC_C_TRIGGER   )
#define PFC_C_INTERRUPT_BIT    (1 << PFC_C_INTERRUPT )
#define PFC_C_EMPTY_BIT        (1 << PFC_C_EMPTY     )
#define PFC_C_READERROR_BIT    (1 << PFC_C_READERROR )
#define PFC_C_TIMEOUT_BIT      (1 << PFC_C_TIMEOUT   )

#define PFC_C_PAGE_LEN         0x07
#define PFC_C_RPAGE_LEN        0x08
#define PFC_C_RPAGE_MASK       0xff

#define PFC_ERR_READ           65
#define PFC_ERR_INTERRUPT      66
#define PFC_ERR_TIMEOUT        67

#define PFC_TILE0_MANAGER      0x00
#define PFC_TILE1_MANAGER      0x01
#define PFC_TILE2_MANAGER      0x02
#define PFC_TILE3_MANAGER      0x03

#define PFC_L2BANK0_MANAGER    0x08
#define PFC_L2BANK1_MANAGER    0x09
#define PFC_L2BANK2_MANAGER    0x0a
#define PFC_L2BANK3_MANAGER    0x0b

#define PFC_TAGCACHE_MANAGER   0x0c

#define PFC_CORE_EG0_RPAGE     0x00
#define PFC_CORE_EG1_RPAGE     0x01
#define PFC_FRONTEND_RPAGE     0x02
#define PFC_L1I_RPAGE          0x03
#define PFC_L1D_RPAGE          0x04
#define PFC_MSHR_RPAGE         0x05

#define PFC_L1ISM_RPAGE        0x80
#define PFC_L1DSM_RPAGE        0x81
#define PFC_L1DSEV_RPAGE       0x82

#define PFC_L2_RPAGEP0         0x00
#define PFC_L2_RPAGEP1         0x01
#define PFC_L2_RITLINK         0x02
#define PFC_L2_ROTLINK         0x03
#define PFC_L2SM_RPAGE         0x80
#define PFC_L2SEV_RPAGE        0x81


#endif /* _PFC_H */
