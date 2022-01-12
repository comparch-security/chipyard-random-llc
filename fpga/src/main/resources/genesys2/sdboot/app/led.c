// See LICENSE for license details.

#include "platform.h"
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>
#include <fcntl.h>
#include <sys/mman.h>

#define   GPIO_REG_BASE        GPIO_CTRL_ADDR
#define   MAP_SIZE             0x400
#define   GPIO_BASE_OFFSET     (GPIO_REG_BASE & 0X00000FFF)
#define   GPIO_PAGE_OFFSET     (GPIO_REG_BASE & 0XFFFFF000)

int main(int argc, char **argv) {
   static int dev_fd;
   dev_fd = open("/dev/mem", O_RDWR);
   if(dev_fd < 0) {
      printf("open(/dev/mem) failed.\n");
      return 0;
   }
   unsigned long d = strtol(argv[1],0 ,0);
   unsigned char *map_base = (unsigned char *)mmap(NULL, MAP_SIZE, PROT_READ | PROT_WRITE, MAP_SHARED, dev_fd, GPIO_PAGE_OFFSET);

   if(map_base == MAP_FAILED)  {
      printf("mmap_fail!\n");
      return 0;
   }
   *(volatile unsigned int *)(map_base+GPIO_BASE_OFFSET+GPIO_IOD_LED)=d;

   if(dev_fd) close(dev_fd);
   munmap(map_base, MAP_SIZE);
   
   return 0;
}
