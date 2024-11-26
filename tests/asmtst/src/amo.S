    .section .text
    .global _start
_start:
    la t0, amo_data

    li t1, 0x12
    sw t1, 0(t0)
    lr.w a0, 0(t0)
    li t2, 0x34
    sc.w a0, t2, 0(t0)

    lr.w a0, 0(t0)
    li t1, 0x56
    sw t1, 0(t0)
    sc.w a0, t2, 0(t0)

    li t1, 0x78
    /* a0 shall be 0x00->0x12->0x00->0x34->SC_FAILED->0x56->0x78 */
    amoswap.w a0, t1, 0(t0)
    lw a0, 0(t0)
end:
    j end

    .section .data
amo_data:
    .space 4
