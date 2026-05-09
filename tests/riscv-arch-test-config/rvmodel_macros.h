#ifndef _RVMODEL_MACROS_H
#define _RVMODEL_MACROS_H

/* ------------------------------------------------------------
 * Test result memory symbols.
 * Your testbench can watch 'tohost':
 *   1 = pass
 *   3 = fail
 * ------------------------------------------------------------ */
#define RVMODEL_DATA_SECTION                                      \
  .pushsection .data,"aw",@progbits;                              \
  .align 8; .global tohost;   tohost:   .dword 0;                 \
  .align 8; .global fromhost; fromhost: .dword 0;                 \
  .popsection;

/* No special boot code for now. */
#define RVMODEL_BOOT

/* ------------------------------------------------------------
 * NS16550A UART from DTS: uart@10000000
 * ------------------------------------------------------------ */
.equ UART_BASE_ADDR, 0x10000000
.equ UART_THR,      (UART_BASE_ADDR + 0)
.equ UART_LCR,      (UART_BASE_ADDR + 3)
.equ UART_LSR,      (UART_BASE_ADDR + 5)

#define RVMODEL_IO_INIT(_R1, _R2, _R3)                            \
  li _R1, UART_LCR;                                                \
  li _R2, 3;                 /* 8N1 */                             \
  sb _R2, 0(_R1);

#define RVMODEL_IO_WRITE_STR(_R1, _R2, _R3, _STR_PTR)              \
1:                                                                  \
  lbu _R1, 0(_STR_PTR);                                             \
  beqz _R1, 3f;                                                     \
2:                                                                  \
  li _R2, UART_LSR;                                                 \
4:                                                                  \
  lbu _R3, 0(_R2);                                                  \
  andi _R3, _R3, 0x20;       /* THR empty */                        \
  beqz _R3, 4b;                                                     \
  li _R2, UART_THR;                                                 \
  sb _R1, 0(_R2);                                                   \
  addi _STR_PTR, _STR_PTR, 1;                                       \
  j 1b;                                                             \
3:

/* ------------------------------------------------------------
 * Termination.
 * Writes to 'tohost' and loops forever.
 * Your simulator/testbench should stop when tohost != 0.
 * ------------------------------------------------------------ */
#define RVMODEL_HALT_PASS                                          \
  li x1, 1;                                                        \
  la t0, tohost;                                                    \
  sd x1, 0(t0);                                                     \
1: j 1b;

#define RVMODEL_HALT_FAIL                                          \
  li x1, 3;                                                        \
  la t0, tohost;                                                    \
  sd x1, 0(t0);                                                     \
1: j 1b;

/* ------------------------------------------------------------
 * Access fault probe address.
 * Pick an unmapped address outside your RAM / MMIO map.
 * ------------------------------------------------------------ */
#define RVMODEL_ACCESS_FAULT_ADDRESS 0x00000000

/* ------------------------------------------------------------
 * CLINT from DTS: clint@02000000
 * ------------------------------------------------------------ */
#define RVMODEL_INTERRUPT_LATENCY 10
#define RVMODEL_TIMER_INT_SOON_DELAY 100

#define RVMODEL_MTIMECMP_ADDRESS 0x02004000
#define RVMODEL_MTIME_ADDRESS    0x0200BFF8

#define CLINT_BASE_ADDRESS 0x02000000
#define MSIP_ADDRESS       (CLINT_BASE_ADDRESS + 0x0)

#define RVMODEL_SET_MSW_INT(_R1, _R2)                              \
  li _R1, 1;                                                        \
  li _R2, MSIP_ADDRESS;                                             \
  sw _R1, 0(_R2);

#define RVMODEL_CLR_MSW_INT(_R1, _R2)                              \
  li _R2, MSIP_ADDRESS;                                             \
  sw zero, 0(_R2);

/* PLIC/external interrupt injection is testbench-specific.
 * Leave blank unless your simulator can inject an external interrupt.
 */
#define RVMODEL_SET_MEXT_INT(_R1, _R2)
#define RVMODEL_CLR_MEXT_INT(_R1, _R2)

/* Supervisor interrupt macros are left blank in first-stage bring-up. */
#define RVMODEL_SET_SEXT_INT(_R1, _R2)
#define RVMODEL_CLR_SEXT_INT(_R1, _R2)
#define RVMODEL_SET_SSW_INT(_R1, _R2)
#define RVMODEL_CLR_SSW_INT(_R1, _R2)

#endif