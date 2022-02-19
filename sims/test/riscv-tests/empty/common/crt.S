#define RAM_BASE 0x80000000

.section .text.start, "ax", @progbits
.globl _start
_start:
  li s0, RAM_BASE
  jr s0

.section ".tohost","aw",@progbits
.align 6
.globl tohost
tohost: .dword 0
.align 6
.globl fromhost
fromhost: .dword 0
