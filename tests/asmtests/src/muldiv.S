    .section .text
    .global _start

    .equ UART, 0x10000000

    /*
     * fail mask bit layout:
     *
     * bits  0 - 14 : independent continuous multiply, x1-x30 / 2
     * bits 15 - 20 : dependent multiply chain
     * bits 21 - 30 : independent mixed mul/div/rem
     * bits 31 - 37 : dependent mixed mul/div/rem
     * bits 38 - 45 : speculative wrong-path squash check
     * bits 46 - 51 : real mixed mul/div after mispredicted forward branch
     * bits 52 - 57 : mixed mul/div after correctly predicted forward-not-taken branch
     */

    .macro CHECK_REG reg, expected, bit
        li      x30, \expected
        beq     \reg, x30, 9000f
        li      x30, (1 << \bit)
        or      x31, x31, x30
9000:
    .endm

_start:
    li      x31, 0                  /* x31 = fail mask */

    /*
     * ------------------------------------------------------------
     * 1. Independent continuous multiply test
     *
     * x1-x30 are used as 15 independent register pairs.
     * The 15 MDU ops below are intentionally back-to-back.
     * Do not insert nops here.
     * ------------------------------------------------------------
     */

    li      x1,  0x0000000000000003
    li      x2,  0x0000000000000005

    li      x3,  -2
    li      x4,  0x4000000000000000

    li      x5,  0xffffffffffffffff
    li      x6,  0x0000000000000002

    li      x7,  -3
    li      x8,  0x8000000000000000

    li      x9,  0x123456789abcdef0
    li      x10, 0x0fedcba987654321

    li      x11, 0x7fffffffffffffff
    li      x12, -1

    li      x13, 0x8000000000000000
    li      x14, 0x0000000000000002

    li      x15, 0x7fffffffffffffff
    li      x16, 0xffffffffffffffff

    li      x17, -123456789
    li      x18, 987654321

    li      x19, -0x123456789abcdef
    li      x20, -0x111111111111111

    li      x21, 0xdeadbeefcafebabe
    li      x22, 0x0102030405060708

    li      x23, 0xc000000000000000
    li      x24, 3

    li      x25, 0
    li      x26, 0x7777777777777777

    li      x27, 0xaaaaaaaaaaaaaaaa
    li      x28, 0x5555555555555555

    li      x29, 0x8000000000000000
    li      x30, 0x8000000000000000

    /* 15 independent MDU ops, intentionally continuous */
    mul     x1,  x1,  x2
    mulh    x3,  x3,  x4
    mulhu   x5,  x5,  x6
    mulhsu  x7,  x7,  x8
    mul     x9,  x9,  x10
    mulh    x11, x11, x12
    mulhu   x13, x13, x14
    mulhsu  x15, x15, x16
    mul     x17, x17, x18
    mulh    x19, x19, x20
    mulhu   x21, x21, x22
    mulhsu  x23, x23, x24
    mul     x25, x25, x26
    mulhu   x27, x27, x28
    mulh    x29, x29, x30

    CHECK_REG x1,  0x000000000000000f, 0
    CHECK_REG x3,  0xffffffffffffffff, 1
    CHECK_REG x5,  0x0000000000000001, 2
    CHECK_REG x7,  0xfffffffffffffffe, 3
    CHECK_REG x9,  0x2236d88fe5618cf0, 4
    CHECK_REG x11, 0xffffffffffffffff, 5
    CHECK_REG x13, 0x0000000000000001, 6
    CHECK_REG x15, 0x7ffffffffffffffe, 7
    CHECK_REG x17, 0xfe4eceeb0400ac7b, 8
    CHECK_REG x19, 0x000136b06e70b742, 9
    CHECK_REG x21, 0x00e06db9f5fd02c3, 10
    CHECK_REG x23, 0xffffffffffffffff, 11
    CHECK_REG x25, 0x0000000000000000, 12
    CHECK_REG x27, 0x38e38e38e38e38e3, 13
    CHECK_REG x29, 0x4000000000000000, 14


    /*
     * ------------------------------------------------------------
     * 2. Dependent multiply chain
     * ------------------------------------------------------------
     */

    li      x5,  0x0000000012345678
    li      x6,  -7

    mul     x7,  x5,  x6
    mulh    x8,  x7,  x5
    mulhu   x9,  x7,  x5
    mulhsu  x10, x8,  x9
    mul     x11, x10, x7
    mulh    x12, x11, x10

    CHECK_REG x7,  0xffffffff8091a2b8, 15
    CHECK_REG x8,  0xffffffffffffffff, 16
    CHECK_REG x9,  0x0000000012345677, 17
    CHECK_REG x10, 0xffffffffffffffff, 18
    CHECK_REG x11, 0x000000007f6e5d48, 19
    CHECK_REG x12, 0xffffffffffffffff, 20


    /*
     * ------------------------------------------------------------
     * 3. Independent mixed mul/div/rem
     *
     * Includes:
     * - signed div overflow: INT64_MIN / -1
     * - divu by zero
     * - remu by zero
     * ------------------------------------------------------------
     */

    li      x5,  0x13579bdf2468ace0
    li      x6,  0xfedcba9876543211

    li      x7,  0x8000000000000000
    li      x8,  -1

    li      x9,  0xffff0000ffff0000
    li      x10, 0x0000ffff0000ffff

    li      x11, 0x8000000000000000
    li      x12, 3

    li      x13, -123456789012345
    li      x14, 1000

    li      x15, 0x9000000000000000
    li      x16, 7

    li      x17, 0x123456789abcdef0
    li      x18, 0x1111111111111111

    li      x19, 0x8000000000000000
    li      x20, -1

    li      x21, 0x123456789abcdef0
    li      x22, 0

    li      x23, 0xfedcba9876543210
    li      x24, 0

    /* intentionally continuous */
    mul     x5,  x5,  x6
    div     x7,  x7,  x8
    mulhu   x9,  x9,  x10
    divu    x11, x11, x12
    rem     x13, x13, x14
    mulh    x15, x15, x16
    remu    x17, x17, x18
    div     x19, x19, x20
    divu    x21, x21, x22
    remu    x23, x23, x24

    CHECK_REG x5,  0xe18485f5d6373ae0, 21
    CHECK_REG x7,  0x8000000000000000, 22
    CHECK_REG x9,  0x0000fffe0002fffc, 23
    CHECK_REG x11, 0x2aaaaaaaaaaaaaaa, 24
    CHECK_REG x13, 0xfffffffffffffea7, 25
    CHECK_REG x15, 0xfffffffffffffffc, 26
    CHECK_REG x17, 0x0123456789abcddf, 27
    CHECK_REG x19, 0x8000000000000000, 28
    CHECK_REG x21, 0xffffffffffffffff, 29
    CHECK_REG x23, 0xfedcba9876543210, 30


    /*
     * ------------------------------------------------------------
     * 4. Dependent mixed mul/div/rem
     * ------------------------------------------------------------
     */

    li      x5,  -123456789
    li      x6,  98765

    mul     x7,  x5,  x6
    div     x8,  x7,  x6
    rem     x9,  x7,  x5
    mulhu   x10, x7,  x8
    divu    x11, x10, x6
    remu    x12, x11, x6
    mulh    x13, x10, x7

    CHECK_REG x7,  0xfffff4e90c10312f, 31
    CHECK_REG x8,  0xfffffffff8a432eb, 32
    CHECK_REG x9,  0x0000000000000000, 33
    CHECK_REG x10, 0xfffff4e904b4646b, 34
    CHECK_REG x11, 0x0000a9deb493e441, 35
    CHECK_REG x12, 0x000000000001753f, 36
    CHECK_REG x13, 0x00000000007afb57, 37


    /*
     * ------------------------------------------------------------
     * 5. Speculative execution test: forward taken
     *
     * If your static predictor treats forward branch as not-taken,
     * this branch should be mispredicted.
     *
     * Fall-through path contains wrong-path MDU ops.
     * They must not write back.
     * ------------------------------------------------------------
     */

    li      x10, 0x1111111111111111
    li      x11, 0x2222222222222222
    li      x12, 0x3333333333333333
    li      x13, 0x4444444444444444
    li      x14, 0x5555555555555555
    li      x15, 0x6666666666666666
    li      x16, 0x7777777777777777
    li      x17, 0x8888888888888888

    li      x18, 0x123456789abcdef0
    li      x19, 0x1111111111111111
    li      x20, -7
    li      x21, 0
    li      x22, 0x8000000000000000
    li      x23, -1
    li      x24, 0xffffffffffffffff
    li      x25, 3

    li      x5, 1
    li      x6, 1
    beq     x5, x6, spec_mispredict_target

    nop
    nop

    /* wrong path: all of these must be squashed */
    mul     x10, x18, x19
    div     x11, x18, x20
    divu    x12, x18, x21
    remu    x13, x18, x21
    mulh    x14, x22, x23
    mulhu   x15, x24, x25
    rem     x16, x22, x23
    mulhsu  x17, x20, x24

    nop
    nop

spec_mispredict_target:
    CHECK_REG x10, 0x1111111111111111, 38
    CHECK_REG x11, 0x2222222222222222, 39
    CHECK_REG x12, 0x3333333333333333, 40
    CHECK_REG x13, 0x4444444444444444, 41
    CHECK_REG x14, 0x5555555555555555, 42
    CHECK_REG x15, 0x6666666666666666, 43
    CHECK_REG x16, 0x7777777777777777, 44
    CHECK_REG x17, 0x8888888888888888, 45

    /*
     * Real mixed MDU ops after the mispredicted branch target.
     */

    li      x5,  -4097
    li      x6,  17
    li      x7,  0xfedcba9876543210
    li      x8,  0x1111111111111111

    mul     x18, x5,  x6
    div     x19, x18, x6
    rem     x20, x18, x6
    mulhu   x21, x7,  x8
    divu    x22, x7,  x8
    remu    x23, x7,  x8

    CHECK_REG x18, 0xfffffffffffeefef, 46
    CHECK_REG x19, 0xffffffffffffefff, 47
    CHECK_REG x20, 0x0000000000000000, 48
    CHECK_REG x21, 0x10fda60a2a059cef, 49
    CHECK_REG x22, 0x000000000000000e, 50
    CHECK_REG x23, 0x0fedcba987654322, 51


    /*
     * ------------------------------------------------------------
     * 6. Speculative execution test: forward not-taken
     *
     * With static forward-not-taken prediction, this one should be
     * correctly predicted.
     * ------------------------------------------------------------
     */

    li      x5, 0
    li      x6, 1

    li      x7,  -0x123456789abc
    li      x8,  0x12345
    li      x9,  0xf0f0f0f0f0f0f0f0
    li      x10, 0x123456789

    beq     x5, x6, spec_correct_taken_target

    /* correct fall-through path */
    mul     x11, x7,  x8
    divu    x12, x9,  x10
    remu    x13, x9,  x10
    mulh    x14, x7,  x8
    div     x15, x7,  x8
    rem     x16, x7,  x8

    j       spec_correct_join

spec_correct_taken_target:
    /*
     * Should never architecturally execute.
     * If it does, final comparison fails.
     */
    li      x11, 0xbad0000000000011
    li      x12, 0xbad0000000000012
    li      x13, 0xbad0000000000013
    li      x14, 0xbad0000000000014
    li      x15, 0xbad0000000000015
    li      x16, 0xbad0000000000016

spec_correct_join:
    CHECK_REG x11, 0xeb499999999a9754, 52
    CHECK_REG x12, 0x00000000d3c3c3c4, 53
    CHECK_REG x13, 0x000000004995510c, 54
    CHECK_REG x14, 0xffffffffffffffff, 55
    CHECK_REG x15, 0xffffffffeffffa50, 56
    CHECK_REG x16, 0xfffffffffffffdb4, 57


    /*
     * ------------------------------------------------------------
     * Print result
     * ------------------------------------------------------------
     */

    beqz    x31, test_pass

test_fail:
    la      a0, msg_fail
    jal     ra, print_str

    mv      a0, x31
    jal     ra, print_hex64

    la      a0, msg_crlf
    jal     ra, print_str

    j       end

test_pass:
    la      a0, msg_pass
    jal     ra, print_str

end:
    j       end


/***************************************************************
 * print_str
 *   a0 = zero-terminated string
 ***************************************************************/
print_str:
    li      t0, UART
1:
    lbu     t1, 0(a0)
    beqz    t1, 2f
    sb      t1, 0(t0)
    addi    a0, a0, 1
    j       1b
2:
    ret


/***************************************************************
 * print_hex64
 *   a0 = value
 *   prints 0x + 16 hex digits
 ***************************************************************/
print_hex64:
    mv      t6, a0
    li      t0, UART

    li      t1, '0'
    sb      t1, 0(t0)
    li      t1, 'x'
    sb      t1, 0(t0)

    li      t2, 60
1:
    srl     t3, t6, t2
    andi    t3, t3, 0xf

    li      t4, 10
    bltu    t3, t4, 2f

    addi    t3, t3, 87         /* 'a' - 10 */
    j       3f

2:
    addi    t3, t3, 48         /* '0' */

3:
    sb      t3, 0(t0)

    addi    t2, t2, -4
    bgez    t2, 1b

    ret


    .section .rodata

msg_pass:
    .asciz "MDU TEST PASS\r\n"

msg_fail:
    .asciz "MDU TEST FAIL, mask = "

msg_crlf:
    .asciz "\r\n"
