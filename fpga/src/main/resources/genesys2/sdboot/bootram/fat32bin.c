// See LICENSE for license details.
#include <stddef.h>

#include "ff.h"
#include "uart.h"
#include "diskio.h"
#include "kprintf.h"
#include "platform.h"

FATFS FatFs;   // Work area (file system object) for logical drive

// max size of file image is 16M
#define MAX_FILE_SIZE 0x1000000

// 4K size read burst
#define SD_READ_SIZE 4096

int main (void)
{
  FIL fil;                // File object
  FRESULT fr;             // FatFs return code
  uint8_t *boot_file_buf = (uint8_t *)(MEMORY_MEM_ADDR);
  //uint8_t *boot_file_buf = (uint8_t *)(MEMORY_MEM_ADDR) + MEMORY_MEM_SIZE - MAX_FILE_SIZE; // at the end of DDR space
 
  uart_init();
  kputln("BOOTRAM: load bin from sdcard with fat32 filesystem");

  // Register work area to the default drive
  if(f_mount(&FatFs, "0://", 1)) {
    kputln("Fail to mount SD driver!");
    return 1;
  }

  // Open a file
  kputln("Load boot.bin into memory");
  fr = f_open(&fil, "0://boot.bin", FA_READ);
  if (fr) {
    kprintln("Failed to open boot! error code %lx\n", fr);
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

  kprintln("Load %lx bytes to memory address %lx from boot.bin of %lx bytes.\n", fsize, boot_file_buf, fil.fsize);


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
}


