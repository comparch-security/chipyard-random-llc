// See LICENSE for license details.

#include "platform.h"
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>
#include <signal.h>
#include <fcntl.h>
#include <sys/mman.h>

#define   L2_CTRL_BASE            L2_CTRL_ADDR
#define   MAP_SIZE                0x400
#define   L2_CTRL_BASE_OFFSET     (L2_CTRL_BASE & 0X00000FFF)
#define   L2_CTRL_PAGE_OFFSET     (L2_CTRL_BASE & 0XFFFFF000)


int main(int argc, char **argv) {

   uint8_t set = 0;
   if(argc != 1) {
     set = 1;
     if(argc != 8) {
        //                      argv[1]             argv[2]      argv[3]    argv[4]         argv[5]      argv[6]         argv[7]
        printf("\nuseage:  [evict_threshold] [access_threshold] [period] [z_threshold0]  [discount0] [z_threshold1]  [discount1] \n");
        printf("example:          1024              8192          4096        40             5           160               3\n");
        printf("threshold 0 means disable\n");
        return 0;
     }
  }

   uint64_t eneth         = 1;
   uint64_t enzth         = 1;
   uint64_t enath         = 1;
   uint64_t eth           = 0;
   uint64_t ath           = 0;
   uint64_t period        = 4096;
   uint64_t zth0          = 0;
   uint64_t discount0     = 0;
   uint64_t zth1          = 0;
   uint64_t discount1     = 0;

   if(set == 1) {
     eth           = atoi(argv[1]);
     ath           = atoi(argv[2]);
     period        = atoi(argv[3]); if(period     >   65536    )   period    =   65536    ; if(period < 1024)  period = 1024;
     zth0          = atoi(argv[4]); if(zth0       >    16*8 - 1)     zth0    =    16*8 - 1;
     discount0     = atoi(argv[5]); if(discount0  >    15      )   discount0 =    15      ;
     zth1          = atoi(argv[6]); if(zth1       >    32*8 - 1)     zth1    =    32*8 - 1;
     discount1     = atoi(argv[7]); if(discount1  >    15      )   discount1 =    15      ;

     if(ath  == 0             ) enath = 0;
     if(eth  == 0             ) eneth = 0;
     if(zth0 == 0 && zth1 == 0) enzth = 0;

     enath      = enath      & ATDECT_CONFIG0_AEN_MASK;
     ath        = ath        & ATDECT_CONFIG0_ATH_MASK;
     eneth      = eneth      & ATDECT_CONFIG0_EEN_MASK;
     eth        = eth        & ATDECT_CONFIG0_ETH_MASK;
     enzth      = enzth      & ATDECT_CONFIG1_ZEN_MASK;
     zth0       = zth0       & ATDECT_CONFIG1_ZTH0_MASK;
     zth1       = zth1       & ATDECT_CONFIG1_ZTH1_MASK;
     discount0  = discount0  & ATDECT_CONFIG1_DISCCOUT0_MASK;
     discount1  = discount1  & ATDECT_CONFIG1_DISCCOUT1_MASK;
     period     = period     & ATDECT_CONFIG1_PERIOD_MASK;
   }
  

   uint64_t atdet_config0 = ( ath       << ATDECT_CONFIG0_ATH_OFFSET       ) +  ( enath  << ATDECT_CONFIG0_AEN_OFFSET     ) +
                            ( eth       << ATDECT_CONFIG0_ETH_OFFSET       ) +  ( eneth  << ATDECT_CONFIG0_EEN_OFFSET     ) ;
   uint64_t atdet_config1 = ( enzth     << ATDECT_CONFIG1_ZEN_OFFSET       ) +  ( period << ATDECT_CONFIG1_PERIOD_OFFSET  ) +
                            ( discount0 << ATDECT_CONFIG1_DISCCOUT0_OFFSET ) +  ( zth0   << ATDECT_CONFIG1_ZTH0_OFFSET    ) +
                            ( discount1 << ATDECT_CONFIG1_DISCCOUT1_OFFSET ) +  ( zth1   << ATDECT_CONFIG1_ZTH1_OFFSET    ) ;

   static int dev_fd;
   dev_fd = open("/dev/mem", O_RDWR);
   if(dev_fd < 0) {
      printf("open(/dev/mem) failed.\n");
      return 0;
   }
   unsigned char *map_base = (unsigned char *)mmap(NULL, 0x400, PROT_READ | PROT_WRITE, MAP_SHARED, dev_fd, L2_CTRL_PAGE_OFFSET);

   if(map_base == MAP_FAILED)  {
      printf("mmap_fail!\n");
      return 0;
   }
   uint64_t check = 0;
   if(set == 1) {
     *(volatile uint64_t *)(map_base+L2_CTRL_BASE_OFFSET+L2_ATDECT_CONFIG0)=0;
     *(volatile uint64_t *)(map_base+L2_CTRL_BASE_OFFSET+L2_ATDECT_CONFIG1)=0;
     sleep(1);
     *(volatile uint64_t *)(map_base+L2_CTRL_BASE_OFFSET+L2_ATDECT_CONFIG0)=atdet_config0;
     *(volatile uint64_t *)(map_base+L2_CTRL_BASE_OFFSET+L2_ATDECT_CONFIG1)=atdet_config1;
     sleep(1);
     check =  *(volatile uint64_t *)(map_base+L2_CTRL_BASE_OFFSET+L2_ATDECT_CONFIG0);
     if(check != atdet_config0) { printf("atdet_config0 not match  req %lx but %lx\n", atdet_config0, check); }
     check =  *(volatile uint64_t *)(map_base+L2_CTRL_BASE_OFFSET+L2_ATDECT_CONFIG1);
     if(check != atdet_config1) { printf("atdet_config1 not match  req %lx but %lx\n", atdet_config1, check); }
   } 
   atdet_config0 = *(volatile uint64_t *)(map_base+L2_CTRL_BASE_OFFSET+L2_ATDECT_CONFIG0);
   atdet_config1 = *(volatile uint64_t *)(map_base+L2_CTRL_BASE_OFFSET+L2_ATDECT_CONFIG1);
  
   uint64_t check_een           =  (  atdet_config0 >> ATDECT_CONFIG0_EEN_OFFSET         ) & ATDECT_CONFIG0_EEN_MASK       ;
   uint64_t check_eth           =  (  atdet_config0 >> ATDECT_CONFIG0_ETH_OFFSET         ) & ATDECT_CONFIG0_ETH_MASK       ;
   uint64_t check_aen           =  (  atdet_config0 >> ATDECT_CONFIG0_AEN_OFFSET         ) & ATDECT_CONFIG0_AEN_MASK       ;
   uint64_t check_ath           =  (  atdet_config0 >> ATDECT_CONFIG0_ATH_OFFSET         ) & ATDECT_CONFIG0_ATH_MASK       ;

   uint64_t check_zen           =  (  atdet_config1 >> ATDECT_CONFIG1_ZEN_OFFSET         ) & ATDECT_CONFIG1_ZEN_MASK       ;
   uint64_t check_period        =  (  atdet_config1 >> ATDECT_CONFIG1_PERIOD_OFFSET      ) & ATDECT_CONFIG1_PERIOD_MASK    ;
   uint64_t check_zth0          =  (  atdet_config1 >> ATDECT_CONFIG1_ZTH0_OFFSET        ) & ATDECT_CONFIG1_ZTH0_MASK      ;
   uint64_t check_zth1          =  (  atdet_config1 >> ATDECT_CONFIG1_ZTH1_OFFSET        ) & ATDECT_CONFIG1_ZTH1_MASK      ;
   uint64_t check_discount0     =  (  atdet_config1 >> ATDECT_CONFIG1_DISCCOUT0_OFFSET   ) & ATDECT_CONFIG1_DISCCOUT0_MASK ;
   uint64_t check_discount1     =  (  atdet_config1 >> ATDECT_CONFIG1_DISCCOUT1_OFFSET   ) & ATDECT_CONFIG1_DISCCOUT1_MASK ;

   printf("en[eaz] [%d%d%d] eth %7ld ath %10ld period %5ld zth0 %3ld discount0 %2ld zth1 %3ld discount1 %2ld \n",
           check_een, check_aen, check_zen, check_eth, check_ath, check_period, check_zth0, check_discount0, check_zth1, check_discount1);

   if(dev_fd) close(dev_fd);
   munmap(map_base, MAP_SIZE);
   
   return 0;
}
