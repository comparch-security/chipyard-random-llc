#include <platform.h>
#include <stdint.h>

typedef struct pfccsr {
   uint64_t c;       //csr_pfc_config;
   uint64_t m;       //csr_pfc_bitmap;
   uint64_t r[64];   //csr_pfc_receive[64];

   uint8_t manager;
   uint8_t page;

   //not csr but some usefull data
   uint8_t  rnum;    //receive how many resp;
   uint64_t raddall; //add all pfc receive;
   uint64_t ramaddr; //ram pfc addr;
} pfccsr_t;

uint8_t get_pfc(uint8_t managerid, uint8_t rpage, uint64_t bitmap, pfccsr_t* pfcreg);


extern char TLEVENTG_NAME[49][22];
extern char COEVENTG0_NAME[37][30]; //CORE ENENT
extern char L2EVENTG0_NAME[16][22];
//extern pfccsr_t CoreEG0[PFC_CORES];
