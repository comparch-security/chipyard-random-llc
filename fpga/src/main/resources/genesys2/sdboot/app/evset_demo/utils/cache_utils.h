
#define DRAM_TEST_ADDR 0X90000000
#define DRAM_TEST_SIZE 0X10000000
#define TESTS               10
#define SETS                1024
#define WAYS                16
#define BLKS                (SETS*WAYS)
#define THRL2MISS           60
#define EVSIZE              (1*WAYS)


uint64_t*    evset[EVSIZE];

void          accessNofence         (void  *p);
void          accessWithfence       (void  *p);
void          clflush_f             (void  *p);
void          clflushl1_f           (void  *p);
uint8_t       clcheck_f             (void  *p);
uint64_t      timeAccess            (void  *p);
uint64_t      timeAccessNoinline    (void  *p)__attribute__((noinline));
uint8_t       accl2hit              (void  *p)__attribute__((noinline));
uint8_t       accl2miss             (void  *p)__attribute__((noinline));
uint16_t      evset_test            (void  *target, uint8_t tests);


int  time_continue_mread (void *adr1, void*adr2);
int  time_continue_mwrite(void *adr1, void*adr2);

