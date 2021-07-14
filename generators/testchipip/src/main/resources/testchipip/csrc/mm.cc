// See LICENSE for license details.

#include "mm.h"
#include <iostream>
#include <fstream>
#include <cstdlib>
#include <cstring>
#include <cassert>
#include <sys/mman.h>
#include <unistd.h>
#include <gelf.h>
#include <fcntl.h>

void mm_t::write(uint64_t addr, uint8_t *data, uint64_t strb, uint64_t size)
{
  strb &= ((1 << size) - 1) << (addr % word_size);
  addr %= this->size;

  uint8_t *base = this->data + (addr / word_size) * word_size;
  for (int i = 0; i < word_size; i++) {
    if (strb & 1)
      base[i] = data[i];
    strb >>= 1;
  }
}

std::vector<char> mm_t::read(uint64_t addr)
{
  addr %= this->size;

  uint8_t *base = this->data + addr;
  return std::vector<char>(base, base + word_size);
}

void mm_t::init(size_t sz, int wsz, int lsz)
{
  assert(wsz > 0 && lsz > 0 && (lsz & (lsz-1)) == 0 && lsz % wsz == 0);
  word_size = wsz;
  line_size = lsz;
  data = (uint8_t *) mmap(
          NULL, sz, PROT_READ|PROT_WRITE, MAP_SHARED|MAP_ANONYMOUS, -1, 0);
  size = sz;
}

mm_t::~mm_t()
{
  munmap(data, this->size);
}

void mm_magic_t::init(size_t sz, int wsz, int lsz)
{
  mm_t::init(sz, wsz, lsz);
  dummy_data.resize(word_size);
}

void mm_magic_t::tick(
  bool reset,

  bool ar_valid,
  uint64_t ar_addr,
  uint64_t ar_id,
  uint64_t ar_size,
  uint64_t ar_len,

  bool aw_valid,
  uint64_t aw_addr,
  uint64_t aw_id,
  uint64_t aw_size,
  uint64_t aw_len,

  bool w_valid,
  uint64_t w_strb,
  void *w_data,
  bool w_last,

  bool r_ready,
  bool b_ready)
{
  bool ar_fire = !reset && ar_valid && ar_ready();
  bool aw_fire = !reset && aw_valid && aw_ready();
  bool w_fire  = !reset && w_valid && w_ready();
  bool r_fire  = !reset && r_valid() && r_ready;
  bool b_fire  = !reset && b_valid() && b_ready;

  if (ar_fire) {
    uint64_t start_addr = (ar_addr / word_size) * word_size;
    for (int i = 0; i <= ar_len; i++) {
      auto dat = read(start_addr + i * word_size);
      rresp.push(mm_rresp_t(ar_id, dat, i == ar_len));
    }
  }

  if (aw_fire) {
    store_addr = aw_addr;
    store_id = aw_id;
    store_count = aw_len + 1;
    store_size = 1 << aw_size;
    store_inflight = true;
  }

  if (w_fire) {
    write(store_addr, (uint8_t *) w_data, w_strb, store_size);
    store_addr += store_size;
    store_count--;

    if (store_count == 0) {
      store_inflight = false;
      bresp.push(store_id);
      assert(w_last);
    }
  }

  if (b_fire)
    bresp.pop();

  if (r_fire)
    rresp.pop();

  cycle++;

  if (reset) {
    while (!bresp.empty()) bresp.pop();
    while (!rresp.empty()) rresp.pop();
    cycle = 0;
  }
}

void mm_t::load_mem(unsigned long start, const char *fname)
{
  std::string line;
  std::ifstream in(fname);
  unsigned long fsize = 0;

  if (!in.is_open()) {
    fprintf(stderr, "Couldn't open loadmem file %s\n", fname);
    abort();
  }

  while (std::getline(in, line))
  {
    #define parse_nibble(c) ((c) >= 'a' ? (c)-'a'+10 : (c)-'0')
    for (ssize_t i = line.length()-2, j = 0; i >= 0; i -= 2, j++) {
      char byte = (parse_nibble(line[i]) << 4) | parse_nibble(line[i+1]);
      ssize_t addr = (start + j) % size;
      data[addr] = byte;
    }
    start += line.length()/2;
    fsize += line.length()/2;

    if (fsize > this->size) {
      fprintf(stderr, "Loadmem file is too large\n");
      abort();
    }
  }
}

void mm_t::load_elf(unsigned long start, const char *fname)
{
  int fd;
  Elf *elf_object;
  int64_t arrNum;
  size_t num;

  fd = open(fname, O_RDONLY , 0);
  if (fd < 0) {
    fprintf(stderr, "load_elf_Err: Cannot open file\n");
    abort();
  }

  if (elf_version(EV_CURRENT) == EV_NONE) {
    fprintf(stderr, "load_elf_Err: elf_version\n");
    abort();
  }

  elf_object = elf_begin(fd , ELF_C_READ , NULL);
  if (elf_object == NULL) {
    fprintf(stderr, "load_elf_Err: %s\n", elf_errmsg(-1));
    abort();
  }

  // Load program headers
  if (elf_getphdrnum(elf_object, &num)) {
    fprintf(stderr, "load_elf_Err: Load program headers\n");
    abort();
  }

  for (size_t i = 0; i < num; i++) {
    //printf("Load program header %zu\n", i);
    GElf_Phdr phdr;
    Elf_Data *elf_data;
    if (gelf_getphdr(elf_object, i, &phdr) != &phdr) {
      fprintf(stderr, "load_elf_Err: Load program headers\n");
      abort();
    }

    elf_data = elf_getdata_rawchunk(elf_object, phdr.p_offset, phdr.p_filesz, ELF_T_BYTE);
    if (elf_data) {
      for (ssize_t ii = 0; ii < elf_data->d_size; ii++) {
        arrNum = phdr.p_paddr+ii-start;
        if(arrNum>=0) data[arrNum] = *(((char*)elf_data->d_buf)+ii);
      }
    }
    Elf32_Word init_with_zero = phdr.p_memsz - phdr.p_filesz;
    if (init_with_zero > 0) {
      void *zeroes = calloc(1, phdr.p_memsz - phdr.p_filesz);
      for (ssize_t ii = 0; ii < init_with_zero; ii++) {
        arrNum=phdr.p_paddr+phdr.p_filesz+ii-start;
        if(arrNum>=0) data[arrNum] = (char)0;
      }
      free(zeroes);
    }
  }
  close(fd);
  //printf("OK\n");
}
