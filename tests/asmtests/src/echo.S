    .section .text
    .global _start

UART_BASE       = 0x10000000
UART_RBR        = 0x00
UART_THR        = 0x00
UART_IER        = 0x01
UART_LSR        = 0x05

UART_IRQ        = 0x0a

PLIC_BASE       = 0x0C000000
PLIC_PRIORITY   = PLIC_BASE + 0x0000
PLIC_ENABLE     = PLIC_BASE + 0x2000
PLIC_THRESHOLD  = PLIC_BASE + 0x200000
PLIC_CLAIM      = PLIC_BASE + 0x200004

_start:
    la sp, stack_bottom

    li t0, UART_BASE
    li t1, 0x01
    sb t1, UART_IER(t0)

    li t0, PLIC_PRIORITY + 4 * UART_IRQ
    li t1, 1
    sw t1, 0(t0)

    li t0, PLIC_ENABLE
    li t1, (1 << UART_IRQ)
    sw t1, 0(t0)

    li t0, PLIC_THRESHOLD
    li t1, 0
    sw t1, 0(t0)

    la t0, handle_interrupt
    csrw mtvec, t0

    li t0, (1 << 11)
    csrs mie, t0

    li t0, (1 << 3)
    csrs mstatus, t0

main_loop:
    j main_loop

handle_interrupt:
    li t0, PLIC_CLAIM
    lw t1, 0(t0)

    li t2, UART_IRQ
    bne t1, t2, complete_interrupt

    li t3, UART_BASE
    lb a0, UART_RBR(t3)

wait_transmit:
    lb t4, UART_LSR(t3)
    andi t4, t4, 0x20
    beqz t4, wait_transmit
    sb a0, UART_THR(t3)

complete_interrupt:
    li t0, PLIC_CLAIM
    sw t1, 0(t0)
    mret

.section .data
stack_top:
    .space 16384
stack_bottom:
