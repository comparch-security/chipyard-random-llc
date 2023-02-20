#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>
#include <signal.h>
#include <fcntl.h>
#include <sys/mman.h>
#include "../../../include/platform.h"

int atdect_config(uint64_t req_eth,  uint64_t req_ath, uint64_t req_period,
                  uint64_t req_zth0, uint64_t req_discount0,
                  uint64_t req_zth1, uint64_t req_discount1) {

   uint64_t eneth         = 1;
   uint64_t enzth         = 1;
   uint64_t enath         = 1;
   uint64_t eth           = req_eth;
   uint64_t ath           = req_ath;
   uint64_t period        = req_period;    
   uint64_t zth0          = req_zth0;
   uint64_t discount0     = req_discount0;
   uint64_t zth1          = req_zth1;
   uint64_t discount1     = req_discount1;
   uint8_t  paraerror     = 0;


   if(period > 65536 || period < 1024)             { printf("period    overlow %ld\n",    period); paraerror = 1; }
   if(zth0        >    16*8 - 1      )             { printf("zth0      overlow %ld\n",      zth0); paraerror = 1; }
   if(discount0   >    15            )             { printf("discount0 overlow %ld\n", discount0); paraerror = 1; }
   if(zth1        >    32*8 - 1      )             { printf("zth1      overlow %ld\n",      zth1); paraerror = 1; }
   if(discount1   >    15            )             { printf("discount1 overlow %ld\n", discount1); paraerror = 1; }
   if(paraerror == 1) {
      //exit(0);
   }



   uint64_t l2_ctrl_base          = L2_CTRL_ADDR;
   uint64_t l2_ctrl_base_offset   = (l2_ctrl_base & 0X00000FFF);
   uint64_t l2_ctrl_page_offset   = (l2_ctrl_base & 0XFFFFF000);

   uint64_t l2_atdect_config0_offset = L2_ATDECT_CONFIG0;
   uint64_t l2_atdect_config1_offset = L2_ATDECT_CONFIG1;

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


   uint64_t atdet_config0 = ( ath       << ATDECT_CONFIG0_ATH_OFFSET       ) +  ( enath  << ATDECT_CONFIG0_AEN_OFFSET     ) +
                            ( eth       << ATDECT_CONFIG0_ETH_OFFSET       ) +  ( eneth  << ATDECT_CONFIG0_EEN_OFFSET     ) ;
   uint64_t atdet_config1 = ( enzth     << ATDECT_CONFIG1_ZEN_OFFSET       ) +  ( period << ATDECT_CONFIG1_PERIOD_OFFSET  ) +
                            ( discount0 << ATDECT_CONFIG1_DISCCOUT0_OFFSET ) +  ( zth0   << ATDECT_CONFIG1_ZTH0_OFFSET    ) +
                            ( discount1 << ATDECT_CONFIG1_DISCCOUT1_OFFSET ) +  ( zth1   << ATDECT_CONFIG1_ZTH1_OFFSET    ) ;

   static int dev_fd;
   dev_fd = open("/dev/mem", O_RDWR);
   if(dev_fd < 0) {
      printf("open(/dev/mem) failed.\n");
      //exit(0);
   }
   unsigned char *map_base = (unsigned char *)mmap(NULL, 0x400, PROT_READ | PROT_WRITE, MAP_SHARED, dev_fd, l2_ctrl_page_offset);

   if(map_base == MAP_FAILED)  {
      printf("mmap_fail!\n");
      //exit(0);
   }
   *(volatile uint64_t *)(map_base+l2_ctrl_base_offset+l2_atdect_config0_offset)= 0;
   *(volatile uint64_t *)(map_base+l2_ctrl_base_offset+l2_atdect_config1_offset)= 0;
   sleep(1); //reset atdect
   *(volatile uint64_t *)(map_base+l2_ctrl_base_offset+l2_atdect_config0_offset)=atdet_config0;
   *(volatile uint64_t *)(map_base+l2_ctrl_base_offset+l2_atdect_config1_offset)=atdet_config1;
   sleep(1);
   uint64_t check =  *(volatile uint64_t *)(map_base+l2_ctrl_base_offset+l2_atdect_config0_offset);
   //printf("check L2_ATDECT_CONFIG0 %lx.\n", check);
   if(check != atdet_config0) { printf("not match "); return 0;}
   sleep(1);
   check =  *(volatile uint64_t *)(map_base+l2_ctrl_base_offset+l2_atdect_config1_offset);
   //printf("check L2_ATDECT_CONFIG1 %lx.\n", check);
   if(check != atdet_config1) { printf("not match "); return 0;}

   if(dev_fd) close(dev_fd);
   munmap(map_base, 0x400);
   
   return 0;
}


void check_atdect_config( uint8_t*  check_een,    uint8_t*  check_aen,    uint8_t*  check_zen,
                          uint64_t* check_eth,    uint64_t* check_ath,    uint64_t* check_period,
                          uint64_t* check_zth0,   uint64_t* check_discount0,
                          uint64_t* check_zth1,   uint64_t* check_discount1) {


   uint64_t l2_ctrl_base          = L2_CTRL_ADDR;
   uint64_t l2_ctrl_base_offset   = (l2_ctrl_base & 0X00000FFF);
   uint64_t l2_ctrl_page_offset   = (l2_ctrl_base & 0XFFFFF000);

   uint64_t l2_atdect_config0_offset = L2_ATDECT_CONFIG0;
   uint64_t l2_atdect_config1_offset = L2_ATDECT_CONFIG1;

   static int dev_fd;
   dev_fd = open("/dev/mem", O_RDWR);
   if(dev_fd < 0) {
      printf("open(/dev/mem) failed.\n");
      //exit(0);
   }
   unsigned char *map_base = (unsigned char *)mmap(NULL, 0x400, PROT_READ | PROT_WRITE, MAP_SHARED, dev_fd, l2_ctrl_page_offset);

   if(map_base == MAP_FAILED)  {
      printf("mmap_fail!\n");
      //exit(0);
   }
   uint64_t atdet_config0 =  *(volatile uint64_t *)(map_base+l2_ctrl_base_offset+l2_atdect_config0_offset);
   uint64_t atdet_config1 =  *(volatile uint64_t *)(map_base+l2_ctrl_base_offset+l2_atdect_config1_offset);


   *check_een           =  (  atdet_config0 >> ATDECT_CONFIG0_EEN_OFFSET         ) & ATDECT_CONFIG0_EEN_MASK       ;
   *check_eth           =  (  atdet_config0 >> ATDECT_CONFIG0_ETH_OFFSET         ) & ATDECT_CONFIG0_ETH_MASK       ;
   *check_aen           =  (  atdet_config0 >> ATDECT_CONFIG0_AEN_OFFSET         ) & ATDECT_CONFIG0_AEN_MASK       ;
   *check_ath           =  (  atdet_config0 >> ATDECT_CONFIG0_ATH_OFFSET         ) & ATDECT_CONFIG0_ATH_MASK       ;

   *check_zen           =  (  atdet_config1 >> ATDECT_CONFIG1_ZEN_OFFSET         ) & ATDECT_CONFIG1_ZEN_MASK       ;
   *check_period        =  (  atdet_config1 >> ATDECT_CONFIG1_PERIOD_OFFSET      ) & ATDECT_CONFIG1_PERIOD_MASK    ;
   *check_zth0          =  (  atdet_config1 >> ATDECT_CONFIG1_ZTH0_OFFSET        ) & ATDECT_CONFIG1_ZTH0_MASK      ;
   *check_zth1          =  (  atdet_config1 >> ATDECT_CONFIG1_ZTH1_OFFSET        ) & ATDECT_CONFIG1_ZTH1_MASK      ;
   *check_discount0     =  (  atdet_config1 >> ATDECT_CONFIG1_DISCCOUT0_OFFSET   ) & ATDECT_CONFIG1_DISCCOUT0_MASK ;
   *check_discount1     =  (  atdet_config1 >> ATDECT_CONFIG1_DISCCOUT1_OFFSET   ) & ATDECT_CONFIG1_DISCCOUT1_MASK ;


   if(dev_fd) close(dev_fd);
   munmap(map_base, 0x400);

}
