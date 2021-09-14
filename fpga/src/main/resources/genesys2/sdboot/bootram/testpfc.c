// See LICENSE for license details.
#include <stddef.h>

#include "ff.h"
#include "elf.h"
#include "pfc.h"
#include "uart.h"
#include "diskio.h"
#include "kprintf.h"
#include "platform.h"

FATFS FatFs;   // Work area (file system object) for logical drive

// max size of file image is 32M
#define MAX_FILE_SIZE 0x2000000

// 4K size read burst
#define SD_READ_SIZE 4096

void log_pfc(pfccsr_t* pfccsr) {
  uint16_t  i;
  uint8_t  rpage     = (pfccsr->c >> PFC_C_PAGE) & PFC_C_RPAGE_MASK;
  uint8_t  managerid = pfccsr->c >> PFC_C_MANAGERID;
  uint64_t bitmap    = pfccsr->m; 
  //tile event
  if(managerid < PFC_L2BANK0_MANAGER) {
    if(rpage == PFC_CORE_EG0_RPAGE) {
      for (i = 0; bitmap; i++, bitmap = bitmap>>1)  {
        if(bitmap&0x01) kprintln("CORE%d_%s: %ld", managerid, COEVENTG0_NAME[i], pfccsr->r[i]);
      }
    }
    /*if(rpage == PFC_L1ISM_RPAGE) {
      for (i = 0; i < pfccsr->rnum; i++)  {
        uint64_t set = i+pfccsr->ramaddr;
        uint64_t pfc = pfccsr->r[i];
        kprintf("CORE%d_L1IMISS_SET", managerid);
        if(set < 10) { kprintf("0"); }   if(set < 100) { kprintf("0"); }
        kprintln("%ld: %ld", set, pfc);
      }
    }
    if(rpage == PFC_L1DSM_RPAGE) {
      for (i = 0; i < pfccsr->rnum; i++)  {
        uint64_t set = i+pfccsr->ramaddr;
        uint64_t pfc = pfccsr->r[i];
        kprintf("CORE%d_L1DMISS_SET", managerid);
        if(set < 10) { kprintf("0"); }   if(set < 100) { kprintf("0"); }
        kprintln("%ld: %ld", set, pfc);
      }  
    }
    if(rpage == PFC_L1DSEV_RPAGE) {
      for (i = 0; i < pfccsr->rnum; i++)  {
        uint64_t set = i+pfccsr->ramaddr;
        uint64_t pfc = pfccsr->r[i];
        kprintf("CORE%d_L1DEV_SET", managerid);
        if(set < 10) { kprintf("0"); }   if(set < 100) { kprintf("0"); }
        kprintln("%ld: %ld", set, pfc);
      }
    }*/
  }
  //l2 event
  else if(managerid < PFC_TAGCACHE_MANAGER) {
    if(rpage == PFC_L2_RPAGEP0) {
      for (i = 0; bitmap; i++, bitmap = bitmap>>1)  {
       if(bitmap&0x01) kprintln("L2BANK%d_%s: %ld", managerid-PFC_L2BANK0_MANAGER, L2EVENTG0_NAME[i], pfccsr->r[i]);
      }
    }
    if(rpage == PFC_L2_RITLINK) {
      for (i = 0; bitmap; i++, bitmap = bitmap>>1)  {
       if(bitmap&0x01) kprintln("L2BANK%dILINK_%s: %ld", managerid-PFC_L2BANK0_MANAGER, TLEVENTG_NAME[i],   pfccsr->r[i]);
      }
    }
    if(rpage == PFC_L2_ROTLINK) {
      for (i = 0; bitmap; i++, bitmap = bitmap>>1)  {
       if(bitmap&0x01) kprintln("L2BANK%dOLINK_%s: %ld", managerid-PFC_L2BANK0_MANAGER, TLEVENTG_NAME[i],   pfccsr->r[i]);
      }
    }
    /*if(rpage == PFC_L2SM_RPAGE) {
      for (i = 0; i < recnum; i++)  {
        uint64_t set = i+pfccsr->ramaddr;
        uint64_t pfc = pfccsr->r[i];
        kprintf("L2BANK%d_MISS_SET", managerid-PFC_L2BANK0_MANAGER);
        if(set < 10) { kprintf("0"); }   if(set < 100) { kprintf("0"); }   if(set < 1000) { kprintf("0"); }
        kprintln("%ld: %ld", set, pfc);
      }
    }
    if(rpage == PFC_L2SEV_RPAGE) {
      for (i = 0; i < recnum; i++)  {
        uint64_t set = i+pfccsr->ramaddr;
        uint64_t pfc = pfccsr->r[i];
        kprintf("L2BANK%d_EV_SET", managerid-PFC_L2BANK0_MANAGER);
        if(set < 10) { kprintf("0"); }   if(set < 100) { kprintf("0"); }   if(set < 1000) { kprintf("0"); }
        kprintln("%ld: %ld", set, pfc);
      }
    }*/
  }
}

void get_pfc_test(uint8_t managerid, uint8_t rpage, uint64_t bitmap, pfccsr_t* pfccsr) {
  uint8_t recnum;
  for(recnum = 0; recnum == 0 || recnum> 64; ) {
    recnum = get_pfc(managerid, rpage, bitmap, pfccsr);
    //kprintln("num: %d pfcc: %lx pfcm: %lx", recnum, pfccsr->c, pfccsr->m); //while(1);
  }
  log_pfc(pfccsr);
}

void get_pfc_testall(void) {
  pfccsr_t pfccsr;
  pfccsr_t cpfccsr;
  pfccsr_t l2ilpfccsr;  // L2 Inner TileLink
  pfccsr_t l2olpfccsr;  // L2 Outer TileLink
  pfccsr.ramaddr=0;

  int32_t i;

  //TILE
  kprintln("\nPFC: CORE EVENT GROUP 0");
  get_pfc_test(PFC_TILE0_MANAGER,     PFC_CORE_EG0_RPAGE, 0xffffffffffffffff, &cpfccsr);

  kprintln("\nPFC: L1I SET MISS");
  for(i=0, pfccsr.raddall=0; i<(PFC_ISETS>>6); i++) {
    get_pfc_test(PFC_TILE0_MANAGER,   PFC_L1ISM_RPAGE,    i,   &pfccsr);
  }
  kprintln("error %ld - %ld = %ld", pfccsr.raddall, cpfccsr.r[31], pfccsr.raddall - cpfccsr.r[31]); 

  kprintln("\nPFC: L1D SET MISS");
  for(i=0, pfccsr.raddall=0; i<(PFC_DSETS>>6); i++) {
    get_pfc_test(PFC_TILE0_MANAGER,   PFC_L1DSM_RPAGE,    i,    &pfccsr);
  }
  kprintln("error %ld - %ld = %ld", pfccsr.raddall, cpfccsr.r[32], pfccsr.raddall - cpfccsr.r[32]); 

  kprintln("\nPFC: L1D SET WRITE_BACK");
  for(i=0, pfccsr.raddall=0; i<(PFC_DSETS>>6); i++) {
    get_pfc_test(PFC_TILE0_MANAGER,   PFC_L1DSEV_RPAGE,   i,     &pfccsr);
  }
  kprintln("error %ld - %ld = %ld", pfccsr.raddall, cpfccsr.r[33], pfccsr.raddall - cpfccsr.r[33]); 

  //L2
  //kprintln("\nPFC: L2 EVENT GROUP 0");
  //get_pfc_test(PFC_L2BANK0_MANAGER,   PFC_L2_RPAGEP0,     0xffffffffffffffff,   &l2pfccsr);

  kprintln("\nPFC: L2 ITLink EVENT");
  get_pfc_test(PFC_L2BANK0_MANAGER,   PFC_L2_RITLINK,     0xffffffffffffffff,   &l2ilpfccsr);

  kprintln("\nPFC: L2 OTLink EVENT");
  get_pfc_test(PFC_L2BANK0_MANAGER,   PFC_L2_ROTLINK,     0xffffffffffffffff,   &l2olpfccsr);

  kprintln("\nPFC: L2 SET MISS");
  for(i=0, pfccsr.raddall=0; i<(PFC_L2SETS>>6); i++) {
    get_pfc_test(PFC_L2BANK0_MANAGER, PFC_L2SM_RPAGE,     i,     &pfccsr);
  }
  kprintln("error %ld - %ld = %ld", pfccsr.raddall, l2olpfccsr.r[7], pfccsr.raddall - l2olpfccsr.r[7]); 

  kprintln("PFC: L2 SET WRITE_BACK");
  for(i=0, pfccsr.raddall=0; i<(PFC_L2SETS>>6); i++) {
    get_pfc_test(PFC_L2BANK0_MANAGER, PFC_L2SEV_RPAGE,    i,     &pfccsr);
  }
  kprintln("error %ld - %ld = %ld", pfccsr.raddall, l2olpfccsr.r[30], pfccsr.raddall - l2olpfccsr.r[30]);

}

int load_elf_from_fat32(void) {
  FIL fil;                // File object
  FRESULT fr;             // FatFs return code
  //uint8_t *memory_base = (uint8_t *)(MEMORY_MEM_ADDR);
  uint8_t *boot_file_buf = (uint8_t *)(MEMORY_MEM_ADDR) + MEMORY_MEM_SIZE - MAX_FILE_SIZE; // at the end of DDR space
 
  uart_init();
  kputln("BOOTRAM: load elf from sdcard with fat32 filesystem");

  // Register work area to the default drive
  if(f_mount(&FatFs, "0://", 1)) {
    kputln("Fail to mount SD driver!");
    return 1;
  }

  // Open a file
  kputln("Load boot.elf into memory");
  fr = f_open(&fil, "0://boot.elf", FA_READ);
  if (fr) {
    kprintln("Failed to open boot! error code %lx", fr);
    return (int)fr;
  }

  // Read file into memory
  uint8_t *buf = boot_file_buf;
  uint32_t fsize = 0;           // file size count
  uint32_t br;                  // Read count
  do {
    fr = f_read(&fil, buf, SD_READ_SIZE, &br);  // Read a chunk of source file
    buf += br;
    fsize += br;
    if((fsize + SD_READ_SIZE) >= MAX_FILE_SIZE) kprintln("Warning: file size is too large");
  } while(!(fr || br == 0));

  kprintln("Load %lx bytes to memory address %lx from boot.elf of %lx bytes.", fsize, boot_file_buf, fil.fsize);

  // read elf
  kprintln("load elf to DDR memory\n");
  br = load_elf(boot_file_buf, fil.fsize);
  if(br) kprintln("elf read failed with code %0d", br);

  // Close the file
  if(f_close(&fil)) {
    kputln("fail to close file!");
    return 1;
  }

  // unmount it
  if(f_mount(NULL, "0://", 1)) {         
    kputln("fail to umount disk!");
    return 1;
  }


  kputln("Boot the loaded program...");
  return 0;
}

int main (void)
{  
  uart_init();
  kputln("TESTPFC: use fat32.elf to test pfc");
  get_pfc_testall();
  while(1) {
    load_elf_from_fat32();
    kputln("Logging pfc...............");
    get_pfc_testall();
  }
}
