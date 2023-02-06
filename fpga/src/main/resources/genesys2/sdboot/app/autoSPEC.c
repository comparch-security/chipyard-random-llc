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

void log_pfc(FILE* fp) {
  for(uint8_t p=0; p<ALLPFCPAGES; p++) {
    pfccsr_t* pfccsr = &c_pfccsr[p];
    uint16_t  i;
    uint8_t  page    = (pfccsr->c >> PFC_C_PAGE) & PFC_C_RPAGE_MASK;
    uint8_t  manager = pfccsr->c >> PFC_C_MANAGERID;
    uint64_t bitmap  = pfccsr->m; 
    //tile event
    if(manager < PFC_L2BANK0_MANAGER) {
      if(page == PFC_CORE_EG0_RPAGE) {
        for (i = 0; bitmap; i++, bitmap >>= 1)  { fprintf(fp, "CORE%d_%s: %ld\n", manager, COEVENTG0_NAME[i], pfccsr->r[i] - s_pfccsr[p].r[i]); }
      }
    }
    //l2 event
    else if(manager < PFC_TAGCACHE_MANAGER) {
      if(page == PFC_L2_RPAGEP1) {
        for (i = 0; bitmap; i++, bitmap >>= 1)  { fprintf(fp, "L2RMPER_%s: %ld\n",       L2EVENTG0_NAME[i],  pfccsr->r[i] - s_pfccsr[p].r[i]); }
      }
      if(page == PFC_L2_RITLINK) {
        for (i = 0; bitmap; i++, bitmap >>= 1)  { fprintf(fp, "L2ILINK_%s: %ld\n",         TLEVENTG_NAME[i],   pfccsr->r[i] - s_pfccsr[p].r[i]); }
      }
      if(page == PFC_L2_ROTLINK) {
        for (i = 0; bitmap; i++, bitmap >>= 1)  { fprintf(fp, "L2OLINK_%s: %ld\n",         TLEVENTG_NAME[i],   pfccsr->r[i] - s_pfccsr[p].r[i]); }
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
  uint8_t sel;
  if(argc != 6) {
    printf("\nuseage:     [start]  [end]  [min] [Ginst] [pfcfile]\n");
    printf("intexample:     1       34    30     100    ./pfc.txt\n");
    printf("fpexample:      35      55    30     100    ./pfc.txt\n");
    return 0;
  }
  uint8_t      start = atoi(argv[1]);
  uint8_t      end   = atoi(argv[2]);
  uint8_t      time  = atoi(argv[3]);
  uint64_t     inst  = ((uint64_t)atoi(argv[4]))*1000*1000*1000;
  cpu_set_t mask[2];
  cpu_set_t get[2];
  CPU_ZERO(&(mask[0]));
  CPU_SET(0, &(mask[0]));
  printf("\n");
  if(sched_setaffinity(0, sizeof(mask[0]), &(mask[0])) < 0) {
    printf("main_thread: set thread affinity failed\n");
  }
  CPU_ZERO((&get[0]));
  if(sched_getaffinity(0, sizeof(get[0]), &(get[0])) < 0) {
    printf("main_thread: get thread affinity failed\n");
  }
  if(!CPU_ISSET(0, &(get[0]))) {
    printf("main_thread is not running in processor %d\n", 0);
  }
  char* pfcfile = argv[5];
  if(start < 1  ) {  start =1;   }
  if(end   < 1  ) {  end   =1;   }
  if(end   > 55 ) {  end   =55;  }
  if(time  < 1  ) {  time  =1;   }
  if(time  > 240) {  time  =240; } //240 min at most
  char* speccmd[100] = {
   "",                                                                                           //0:  nothing
   //INT
   "./perlbench -I./lib checkspam.pl 2500 5 25 11 150 1 1 1 1",                                  //1:  400.perlbench
   "./perlbench -I./lib diffmail.pl 4 800 10 17 19 300",                                         //2:  400.perlbench
   "./perlbench -I./lib splitmail.pl 1600 12 26 16 4500",                                        //3:  400.perlbench
   "./bzip2 input.source 280",                                                                   //4:  401.bzip2
   "./bzip2 chicken.jpg 30",                                                                     //5:  401.bzip2
   "./bzip2 liberty.jpg 30",                                                                     //6:  401.bzip2
   "./bzip2 input.program 280",                                                                  //7:  401.bzip2
   "./bzip2 text.html 280",                                                                      //8:  401.bzip2
   "./bzip2 input.combined 200",                                                                 //9:  401.bzip2
   "./gcc 166.i -o 166.s",                                                                       //10: 403.gcc
   "./gcc 200.i -o 200.s",                                                                       //11: 403.gcc
   "./gcc c-typeck.i -o c-typeck.s",                                                             //12: 403.gcc
   "./gcc cp-decl.i -o cp-decl.s",                                                               //13: 403.gcc
   "./gcc expr.i -o expr.s",                                                                     //14: 403.gcc
   "./gcc expr2.i -o expr2.s",                                                                   //15: 403.gcc
   "./gcc g23.i -o g23.s",                                                                       //16: 403.gcc
   "./gcc s04.i -o s04.s",                                                                       //17: 403.gcc
   "./gcc scilab.i -o scilab.s",                                                                 //18: 403.gcc
   "./gobmk --quiet --mode gtp < 13x13.tst",                                                     //19: 445.gobmk
   "./gobmk --quiet --mode gtp < nngs.tst",                                                      //20: 445.gobmk
   "./gobmk --quiet --mode gtp < score2.tst",                                                    //21: 445.gobmk
   "./gobmk --quiet --mode gtp < trevorc.tst",                                                   //22: 445.gobmk
   "./gobmk --quiet --mode gtp < trevord.tst",                                                   //23: 445.gobmk
   "./mcf inp.in",                                                                               //24: 429.mcf
   "./hmmer nph3.hmm swiss41",                                                                   //25: 456.hmmer
   "./hmmer --fixed 0 --mean 500 --num 500000 --sd 350 --seed 0 retro.hmm",                      //26: 456.hmmer
   "./sjeng ref.txt",                                                                            //27: 458.sjeng
   "./libquantum 1397 8",                                                                        //28: 462.libquantum
   "./h264ref -d foreman_ref_encoder_baseline.cfg",                                              //29: 464.h264ref
   "./h264ref -d foreman_ref_encoder_main.cfg",                                                  //30: 464.h264ref
   "./h264ref -d sss_encoder_main.cfg",                                                          //31: 464.h264ref
   "./omnetpp omnetpp.ini",                                                                      //32: 471.omnetpp
   "./astar BigLakes2048.cfg",                                                                   //33: 473.astar
   "./astar rivers.cfg",                                                                         //34: 473.astar
   "./Xalan -v t5.xml xalanc.xsl",                                                               //35: 483.Xalan
   //FP
   "./bwaves < bwaves.in",                                                                       //36: 410.bwaves
   "./gamess < cytosine.2.config",                                                               //37: 416.gamess
   "./gamess < h2ocu2+.gradient.config",                                                         //38: 416.gamess
   "./gamess < triazolium.config",                                                               //39: 416.gamess
   "./milc < su3imp.in",                                                                         //40: 433.milc
   "./zeusmp < zmp_inp",                                                                         //41: 434.zeusmp
   "./gromacs -silent -deffnm gromacs -nice 0",                                                  //42: 435.gromacs
   "./cactusADM benchADM.par",                                                                   //43: 436.cactusADM
   "./leslie3d < leslie3d.in",                                                                   //44: 437.leslie3d
   "./namd --input namd.input --iterations 38 --output namd.out",                                //45: 444.namd
   "./dealII 23",                                                                                //46: 447.dealII
   "./soplex -s1 -e -m45000 pds-50.mps",                                                         //47: 450.soplex
   "./soplex -m3500 ref.mps",                                                                    //48: 450.soplex
   "./povray SPEC-benchmark-ref.ini",                                                            //49: 453.povray
   "./calculix -i hyperviscoplastic",                                                            //50: 454.calculix
   "./GemsFDTD < ref.in",                                                                        //51: 459.GemsFDTD
   "./tonto < stdin",                                                                            //52: 465.tonto
   "./lbm 3000 reference.dat 0 0 100_100_130_ldc.of",                                            //53: 470.lbm
   "./wrf < namelist.input",                                                                     //54: 481.wrf
   "./sphinx_livepretend_base.riscv ctlfile . args.an4",                                         //55: 482.sphinx
  };
  for(sel=start; sel<=end; sel++) {
    if(sel==24) sel=25;   //24: 429.mcf
    if(sel==41) sel=42;   //41: 433.zeusmp
     pid = fork();
     if(pid==0) { //child
       CPU_ZERO(&(mask[1]));
       CPU_SET(0, &(mask[1]));
       if(sched_setaffinity(0, sizeof(mask[1]), &(mask[1])) < 0) {
         printf("spec2006_thread: set thread affinity failed\n");
       }
       CPU_ZERO(&(get[1]));
       if(sched_getaffinity(0, sizeof(get[1]), &(get[1])) < 0) {
         printf("spec2006_thread: get thread affinity failed\n");
       }
       if(!CPU_ISSET(0, &(get[1]))) {
         printf("spec2006_thread is not running in processor %d\n", 0);
       }
       chdir("/mnt/riscv-spec-ref");
       //INT
       if(sel <=  3)      { chdir("./400.perlbench");   }
       else if(sel <=  9) { chdir("./401.bzip2");       }
       else if(sel <= 18) { chdir("./403.gcc");         }
       else if(sel <= 23) { chdir("./445.gobmk");       }
       else if(sel <= 24) { chdir("./429.mcf");         }
       else if(sel <= 26) { chdir("./456.hmmer");       }
       else if(sel <= 27) { chdir("./458.sjeng");       }
       else if(sel <= 28) { chdir("./462.libquantum");  }
       else if(sel <= 31) { chdir("./464.h264ref");     }
       else if(sel <= 32) { chdir("./471.omnetpp");     }
       else if(sel <= 34) { chdir("./473.astar");       }
       else if(sel <= 35) { chdir("./483.xalancbmk");   }
       //FP
       else if(sel <= 36) { chdir("./410.bwaves");      }
       else if(sel <= 39) { chdir("./416.gamess");      }
       else if(sel <= 40) { chdir("./433.milc");        }
       else if(sel <= 41) { chdir("./434.zeusmp");      }
       else if(sel <= 42) { chdir("./435.gromacs");     }
       else if(sel <= 43) { chdir("./436.cactusADM");   }
       else if(sel <= 44) { chdir("./437.leslie3d");    }
       else if(sel <= 45) { chdir("./444.namd");        }
       else if(sel <= 46) { chdir("./447.dealII");      }
       else if(sel <= 48) { chdir("./450.soplex");      }
       else if(sel <= 49) { chdir("./453.povray");      }
       else if(sel <= 50) { chdir("./454.calculix");    }
       else if(sel <= 51) { chdir("./459.GemsFDTD");    }
       else if(sel <= 52) { chdir("./465.tonto");       }
       else if(sel <= 53) { chdir("./470.lbm");         }
       else if(sel <= 54) { chdir("./481.wrf");         }
       else if(sel <= 55) { chdir("./482.sphinx3");     }
       printf("\n--------------------------log_%d--------------------------\n", sel);
       printf(speccmd[sel]);
       printf("\n");
       fflush(stdout);
       execlp("/bin/sh", "sh", "-c", speccmd[sel], NULL);
       exit(0);
     } else {
       if(sel) {
         CPU_ZERO((&get[0]));
         if(sched_getaffinity(pthread_self(), sizeof(get[0]), &(get[0])) < 0) {
           printf("main_thread: get thread affinity failed\n");
         }
         if(!CPU_ISSET(0, &(get[0]))) {
           printf("main_thread %ld is not running in processor %d\n", pthread_self(), 0);
         }
         pid_t    wait_rv = pid;
         int      child_status;
         uint8_t  child_exited = 0;
         uint64_t e_inst;  //end_instructions
         uint64_t n_inst;  //now_instructions
         uint16_t run_time = time;
         led(sel);
         FILE* fp=NULL;
         config_pfc();
         sched_yield();
         //sleep(60); //warm up
         get_pfc_all(s_pfccsr);
         e_inst = get_pfc_inst()+inst;
         sleep(time*60);
         n_inst = get_pfc_inst();
         while(n_inst < e_inst) {
           for(uint8_t i = 0; i<10; i++) {
             sleep(6);
             //https://www.ibm.com/docs/en/zos/2.1.0?topic=functions-waitpid-wait-specific-child-process-end
             //https://man7.org/linux/man-pages/man2/wait.2.html
             //if WNOHANG was specified and one or more child(ren) specified by pid exist, but have not yet changed state, then 0 is returned
             wait_rv = waitpid(pid, &child_status, WNOHANG);
             if(wait_rv != 0) {
               if((wait_rv == -1 || WIFEXITED(child_status) || WIFSIGNALED(child_status))) {
                 child_exited = 1;
                 break;
               }
             }
           }
           if(child_exited == 1) break;
           run_time = run_time+1;
           n_inst = get_pfc_inst();
         }
         get_pfc_all(c_pfccsr);
         kill(pid, SIGKILL);
         fp = fopen(pfcfile, "a+");
         if(fp == NULL) {
           printf("\nFaild to open file!!\n");
         }
         fprintf(fp, "\n--------------------------pfc_%d--------------------------\n", sel);
         fprintf(fp, speccmd[sel]);
         fprintf(fp, "\n");
         fprintf(fp, "run_time: %d\n", run_time);
         log_pfc(fp);
         fclose(fp);
       }
     }
  }
  led(0);
}
