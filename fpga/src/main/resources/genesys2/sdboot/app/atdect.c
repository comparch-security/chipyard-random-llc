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
   if(argc != 9) {
    printf("\nuseage: [eneth] [evict_threshold] [enath] [access_threshold] [enzth] [z_threshold] [period] [discount] \n");
    printf("example:     1         1024           1         8192             1          5          4096        5     \n");
    return 0;
  }

   uint8_t  eneth         = atoi(argv[1]); if(eneth    >     0)   eneth   =     1;
   uint32_t eth           = atoi(argv[2]);
   uint8_t  enath         = atoi(argv[3]); if(enath    >     0)   enath   =     1;
   uint32_t ath           = atoi(argv[4]);
   uint8_t  enzth         = atoi(argv[5]); if(enzth    >     0)   enzth   =     1;
   uint32_t zth           = atoi(argv[6]); if(zth      >    16)     zth   =    15;
   uint32_t period        = atoi(argv[7]); if(period   > 65536)  period   = 65536; if(period < 1024)  period = 1024;
   uint32_t discount      = atoi(argv[8]); if(discount >     8)  discount =     8;
   uint64_t atdet_config0 = (uint64_t)((((uint64_t)ath << 1) + enath) << 32) + (uint32_t)((eth << 1) + eneth);
   uint64_t atdet_config1 = (uint64_t)(((((discount << 20) + period) << 4) + zth) << 1) + enzth;
   volatile uint64_t check;

   static int dev_fd;
   dev_fd = open("/dev/mem", O_RDWR);
   if(dev_fd < 0) {
      printf("open(/dev/mem) failed.\n");
      return 0;
   }
   unsigned char *map_base = (unsigned char *)mmap(NULL, MAP_SIZE, PROT_READ | PROT_WRITE, MAP_SHARED, dev_fd, L2_CTRL_PAGE_OFFSET);

   if(map_base == MAP_FAILED)  {
      printf("mmap_fail!\n");
      return 0;
   }
   *(volatile uint64_t *)(map_base+L2_CTRL_BASE_OFFSET+L2_ATDECT_CONFIG1)= 0;
   *(volatile uint64_t *)(map_base+L2_CTRL_BASE_OFFSET+L2_ATDECT_CONFIG0)= 0;
   sleep(1); //reset atdect
   *(volatile uint64_t *)(map_base+L2_CTRL_BASE_OFFSET+L2_ATDECT_CONFIG0)=atdet_config0;
   *(volatile uint64_t *)(map_base+L2_CTRL_BASE_OFFSET+L2_ATDECT_CONFIG1)=atdet_config1;
   sleep(1);
   check =  *(volatile uint64_t *)(map_base+L2_CTRL_BASE_OFFSET+L2_ATDECT_CONFIG0);
   printf("check L2_ATDECT_CONFIG0 %lx.\n", check);
   if(check != atdet_config0) { printf("not match "); return 0;}
   sleep(1);
   check =  *(volatile uint64_t *)(map_base+L2_CTRL_BASE_OFFSET+L2_ATDECT_CONFIG1);
   printf("check L2_ATDECT_CONFIG1 %lx.\n", check);
   if(check != atdet_config1) { printf("not match "); return 0;}

   if(dev_fd) close(dev_fd);
   munmap(map_base, MAP_SIZE);
   
   return 0;
}
