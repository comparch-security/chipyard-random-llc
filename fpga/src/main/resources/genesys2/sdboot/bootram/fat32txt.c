// SD test program
#include <stddef.h>

#include "ff.h"
#include "uart.h"
#include "diskio.h"
#include "kprintf.h"
#include "platform.h"


/* Read a text file and display it */

FATFS FatFs;   /* Work area (file system object) for logical drive */

int main (void)
{
  FIL fil;                /* File object */
  uint8_t buffer[64];     /* File copy buffer */
  FRESULT fr;             /* FatFs return code */
  uint32_t br;            /* Read count */

  uart_init();
  kputln("BOOTRAM: read txt from sdcard which has fat32 filesystem");

  /* Register work area to the default drive */
  if(f_mount(&FatFs, "0://", 1)) {
    kputln("Fail to mount SD driver!");
    return 1;
  }

  /* Open a text file */
  fr = f_open(&fil, "0://test.txt", FA_READ);
  if (fr) {
    kputln("failed to open test.text!");
    return (int)fr;
  } else {
    kputln("test.txt opened");
  }

  /* Read all lines and display it */
  uint32_t fsize = 0;
  for (;;) {
    fr = f_read(&fil, buffer, sizeof(buffer)-1, &br);  /* Read a chunk of source file */
    if (fr || br == 0) break; /* error or eof */
    buffer[br] = 0;
    kprintln("%s", buffer);
    fsize += br;
  }

  kprintln("file size %lx", fsize);

  /* Close the file */
  if(f_close(&fil)) {
    kputln("fail to close file!");
    return 1;
  }
  if(f_mount(NULL, "0://", 1)) {         /* unmount it */
    kputln("fail to umount disk!");
    return 1;
  }

  kputln("test.txt closed");

  return 0;
}
