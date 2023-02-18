#define _GNU_SOURCE
#include "pfc.h"
#include "platform.h"
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>
#include <signal.h>
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/wait.h>
#include <pthread.h>
#include <sched.h>

#define   CORES           1
#define   CPFCPAGES       1                  // only 1 pfcpage / core
#define   L2PFCPAGES      3                  //      2 pfcpage / l2
#define   ALLPFCPAGES    CORES*CPFCPAGES+L2PFCPAGES

pfccsr_t s_pfccsr[ALLPFCPAGES];   //start   pfc
pfccsr_t c_pfccsr[ALLPFCPAGES];   //current pfc

void led(uint8_t d) {
   #define   MAP_SIZE              0x400
   #define   GPIO_BASE_OFFSET     (GPIO_CTRL_ADDR & 0X00000FFF)
   #define   GPIO_PAGE_OFFSET     (GPIO_CTRL_ADDR & 0XFFFFF000)
   static int dev_fd;
   uint8_t *map_base;
   dev_fd = open("/dev/mem", O_RDWR);
   if(dev_fd < 0) { return ; }
   map_base = (uint8_t *)mmap(NULL, MAP_SIZE, PROT_READ | PROT_WRITE, MAP_SHARED, dev_fd, GPIO_PAGE_OFFSET);

   if(map_base == MAP_FAILED)  { return ; }
   *(volatile uint8_t *)(map_base+GPIO_BASE_OFFSET+GPIO_IOD_LED)=d;

   close(dev_fd);
   munmap(map_base, MAP_SIZE);
}

void config_pfc(void) {
  for(uint8_t p=0; p<ALLPFCPAGES; p++) {
    if(p<CORES*CPFCPAGES) {
      uint8_t manager     = PFC_TILE0_MANAGER  + p/CPFCPAGES;
      uint8_t page        = PFC_CORE_EG0_RPAGE + p%CPFCPAGES;
      s_pfccsr[p].manager = manager;
      c_pfccsr[p].manager = manager;
      s_pfccsr[p].page    = page;
      c_pfccsr[p].page    = page;
    } else if(p<(CORES*CPFCPAGES+L2PFCPAGES)) {
      uint8_t manager     = PFC_L2BANK0_MANAGER;
      uint8_t page        = PFC_L2_RPAGEP1  + p - CORES*CPFCPAGES;
      s_pfccsr[p].manager = manager;
      c_pfccsr[p].manager = manager;
      s_pfccsr[p].page    = page;
      c_pfccsr[p].page    = page;
    }
  }
}

void log_pfc(void) {
  for(uint8_t p=0; p<ALLPFCPAGES; p++) {
    pfccsr_t* pfccsr = &c_pfccsr[p];
    uint16_t  i;
    uint8_t  page    = (pfccsr->c >> PFC_C_PAGE) & PFC_C_RPAGE_MASK;
    uint8_t  manager = pfccsr->c >> PFC_C_MANAGERID;
    uint64_t bitmap  = pfccsr->m; 
    //tile event
    if(manager < PFC_L2BANK0_MANAGER) {
      if(page == PFC_CORE_EG0_RPAGE) {
        for (i = 0; bitmap; i++, bitmap >>= 1)  { printf("CORE%d_%s: %ld\n", manager, COEVENTG0_NAME[i], pfccsr->r[i] - s_pfccsr[p].r[i]); }
      }
    }
    //l2 event
    else if(manager < PFC_TAGCACHE_MANAGER) {
      if(page == PFC_L2_RPAGEP1) {
        for (i = 0; bitmap; i++, bitmap >>= 1)  { printf("L2RMPER_%s: %ld\n",       L2EVENTG0_NAME[i],  pfccsr->r[i] - s_pfccsr[p].r[i]); }
      }
      if(page == PFC_L2_RITLINK) {
        for (i = 0; bitmap; i++, bitmap >>= 1)  { printf("L2ILINK_%s: %ld\n",         TLEVENTG_NAME[i],   pfccsr->r[i] - s_pfccsr[p].r[i]); }
      }
      if(page == PFC_L2_ROTLINK) {
        for (i = 0; bitmap; i++, bitmap >>= 1)  { printf("L2OLINK_%s: %ld\n",         TLEVENTG_NAME[i],   pfccsr->r[i] - s_pfccsr[p].r[i]); }
      }
    }
  }
}

void get_pfc_all(pfccsr_t* pfccsr) {
  uint8_t  rec;
  for(uint8_t p=0; p<ALLPFCPAGES; p++) { 
    for(rec = 0; rec == 0 || rec> 64; ) { rec =  get_pfc(pfccsr[p].manager, pfccsr[p].page, 0xffffffffffffffff, &pfccsr[p] ); }
  }
}

uint64_t get_pfc_inst() {
  pfccsr_t  pfccsr;
  uint64_t  inst=0;
  uint8_t   rec;
  for(uint8_t p=0; p<CORES; p++) {
    for(rec = 0; rec == 0 || rec> 64; ) { rec = get_pfc(PFC_TILE0_MANAGER+p, PFC_CORE_EG0_RPAGE, 0x0000000000000002, &pfccsr);}
    inst = inst+pfccsr.r[1];
  }
  return inst;
}

uint64_t main (int argc, char *argv[])
{
  pid_t pid;
  pid = fork();
  if (pid == 0) { //child
    //chdir("/mnt/");
    execvp(argv[1],argv+1);
    exit(0);
  }
  else {
   config_pfc();
   get_pfc_all(s_pfccsr);
   waitpid(pid, NULL, 0);
   get_pfc_all(c_pfccsr);
   log_pfc();
  }
}
