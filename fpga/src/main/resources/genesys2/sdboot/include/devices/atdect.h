// See LICENSE.Sifive for license details.

#ifndef _ATDECT_CONFIG_H
#define _ATDECT_CONFIG_H

#define ATDECT_CONFIG0_ATH_OFFSET           33
#define ATDECT_CONFIG0_AEN_OFFSET           32
#define ATDECT_CONFIG0_ETH_OFFSET            1
#define ATDECT_CONFIG0_EEN_OFFSET            0

#define ATDECT_CONFIG0_ATH_MASK            (0x000000007FFFFFFF)
#define ATDECT_CONFIG0_AEN_MASK            (0x0000000000000001)
#define ATDECT_CONFIG0_ETH_MASK            (0x000000007FFFFFFF)
#define ATDECT_CONFIG0_EEN_MASK            (0x0000000000000001)

#define ATDECT_CONFIG1_DISCCOUT1_OFFSET       40
#define ATDECT_CONFIG1_ZTH1_OFFSET            32
#define ATDECT_CONFIG1_DISCCOUT0_OFFSET       28
#define ATDECT_CONFIG1_ZTH0_OFFSET            21
#define ATDECT_CONFIG1_PERIOD_OFFSET           1
#define ATDECT_CONFIG1_ZEN_OFFSET              0

#define ATDECT_CONFIG1_DISCCOUT1_MASK      (0x000000000000000F)
#define ATDECT_CONFIG1_ZTH1_MASK           (0x00000000000000FF)
#define ATDECT_CONFIG1_DISCCOUT0_MASK      (0x000000000000000F)
#define ATDECT_CONFIG1_ZTH0_MASK           (0x000000000000007F)
#define ATDECT_CONFIG1_PERIOD_MASK         (0x00000000000FFFFF)
#define ATDECT_CONFIG1_ZEN_MASK            (0x0000000000000001)




#endif /* _ATDECT_CONFIG_H */
