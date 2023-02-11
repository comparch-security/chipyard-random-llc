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
   if(argc != 7) {
    //                      argv[1]             argv[2]      argv[3]    argv[4]         argv[5]      argv[6]         argv[7]
    printf("\nuseage:  [evict_threshold] [access_threshold] [period] [z_threshold0]  [discount0] [z_threshold1]  [discount1] \n");
    printf("example:          1024              8192          4096        5             5           20               3\n");
    printf("threshold 0 means disable\n");
    return 0;
  }

   uint64_t eneth         = 1;
   uint64_t enzth         = 1;
   uint64_t enath         = 1;
   uint64_t eth           = atoi(argv[1]);
   uint64_t ath           = atoi(argv[2]);
   uint64_t period        = atoi(argv[3]); if(period     > 65536)   period    = 65536; if(period < 1024)  period = 1024;
   uint64_t zth0          = atoi(argv[4]); if(zth0       >    15)     zth0    =    15;
   uint64_t discount0     = atoi(argv[5]); if(discount0  >    15)   discount0 =    15;
   uint64_t zth1          = atoi(argv[6]); if(zth1       >    31)     zth1    =    31;
   uint64_t discount1     = atoi(argv[7]); if(discount1  >    15)   discount1 =    15;

   if(ath  == 0             ) enath = 0;
   if(eth  == 0             ) eneth = 0;
   if(zth0 == 0 && zth1 == 0) enzth = 0;

   uint64_t atdet_config0 = (uint64_t)((((uint64_t)ath << 1) + enath) << 32) + (uint32_t)((eth << 1) + eneth);
   uint64_t atdet_config1 = (discount1 << 34) + (zth1 << 29) + (discount0 << 25)  + (zth0 << 21) + (period << 1) + enzth;

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
