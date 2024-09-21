# Immediate Instructions
lui x1, 0x12345       # x1 = 0x12345000
auipc x2, 0x1         # x2 = PC + 0x1000
addi x3, x1, 0x678    # x3 = x1 + 0x678
addiw x4, x2, 0x9     # x4 = (int32_t)(x2 + 0x9)
slti x5, x3, 0x100    # x5 = (x3 < 0x100) ? 1 : 0
sltiu x6, x3, 0x100   # x6 = (x3 < 0x100) ? 1 : 0 (unsigned)
xori x7, x3, 0x555    # x7 = x3 ^ 0x555
ori x8, x3, 0x555     # x8 = x3 | 0x555
andi x9, x3, 0x7ff    # x9 = x3 & 0x7ff
slli x10, x3, 4       # x10 = x3 << 4
slliw x11, x3, 2      # x11 = (int32_t)x3 << 2
srli x12, x3, 3       # x12 = x3 >> 3 (logical)
srliw x13, x3, 1      # x13 = (uint32_t)x3 >> 1
srai x14, x3, 2       # x14 = x3 >> 2 (arithmetic)
sraiw x15, x3, 3      # x15 = (int32_t)x3 >> 3

# Register-Register Instructions
add x16, x1, x3       # x16 = x1 + x3
sub x17, x1, x3       # x17 = x1 - x3
addw x18, x1, x3      # x18 = (int32_t)(x1 + x3)
subw x19, x1, x3      # x19 = (int32_t)(x1 - x3)
sll x20, x1, x3       # x20 = x1 << x3
sllw x21, x1, x3      # x21 = (int32_t)x1 << (x3 & 0x1F)
slt x22, x1, x3       # x22 = (x1 < x3) ? 1 : 0
sltu x23, x1, x3      # x23 = (x1 < x3) ? 1 : 0 (unsigned)
xor x24, x1, x3       # x24 = x1 ^ x3
srl x25, x1, x3       # x25 = x1 >> x3 (logical)
srlw x26, x1, x3      # x26 = (uint32_t)x1 >> (x3 & 0x1F)
sra x27, x1, x3       # x27 = x1 >> x3 (arithmetic)
sraw x28, x1, x3      # x28 = (int32_t)x1 >> (x3 & 0x1F)
or x29, x1, x3        # x29 = x1 | x3
and x30, x1, x3       # x30 = x1 & x3

# Final result
sd x30, 1016(x0)
sd x29, 1016(x0)
sd x28, 1016(x0)
sd x27, 1016(x0)
sd x26, 1016(x0)
sd x25, 1016(x0)
sd x24, 1016(x0)
sd x23, 1016(x0)
sd x22, 1016(x0)
sd x21, 1016(x0)
sd x20, 1016(x0)
sd x19, 1016(x0)
sd x18, 1016(x0)
sd x17, 1016(x0)
sd x16, 1016(x0)
sd x15, 1016(x0)
sd x14, 1016(x0)
sd x13, 1016(x0)
sd x12, 1016(x0)
sd x11, 1016(x0)
sd x10, 1016(x0)
sd x9, 1016(x0)
sd x8, 1016(x0)
sd x7, 1016(x0)
sd x6, 1016(x0)
sd x5, 1016(x0)
sd x4, 1016(x0)
sd x3, 1016(x0)
sd x2, 1016(x0)
sd x1, 1016(x0)