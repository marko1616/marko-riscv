### TODO List（Instruction）

<details>
<summary><strong> Immediate Instructions </strong>✅</summary>

- [x] lui
- [x] auipc
- [x] addi
- [x] addiw
- [x] slti
- [x] sltiu
- [x] xori
- [x] ori
- [x] andi
- [x] slli
- [x] slliw
- [x] srli
- [x] srliw
- [x] srai
- [x] sraiw
</details>

<details>
<summary><strong> Register-Register Instructions </strong>✅</summary>

- [x] add
- [x] sub
- [x] addw
- [x] subw
- [x] sll
- [x] sllw
- [x] slt
- [x] sltu
- [x] xor
- [x] srl
- [x] srlw
- [x] sra
- [x] sraw
- [x] or
- [x] and
</details>

<details>
<summary><strong> Memory Instructions </strong>✅</summary>

- [x] lb
- [x] lh
- [x] lw
- [x] ld
- [x] lbu
- [x] lhu
- [x] lwu
- [x] sb
- [x] sh
- [x] sw
- [x] sd
</details>

<details>
<summary><strong> Control Instructions </strong>✅</summary>

- [x] jal
- [x] jalr
- [x] beq
- [x] bne
- [x] blt
- [x] bge
- [x] bltu
- [x] bgeu
</details>

<details>
<summary><strong> Fence </strong></summary>

- [ ] fence
</details>

<details>
<summary><strong> System Instructions </strong></summary>

- [ ] ecall
- [ ] ebreak
</details>

<details>
<summary><strong> Zifencei Extension Instructions </strong></summary>

- [ ] fence.i
</details>

<details>
<summary><strong> Zicsr Extension Instructions</strong></summary>

- [ ] csrrw
- [ ] csrrs
- [ ] csrrc
- [ ] csrrwi
- [ ] csrrsi
- [ ] csrrci
</details>

<details>
<summary><strong> Machine mode </strong></summary>

- [ ] wfi
- [ ] mret
</details>

<details>
<summary><strong> Supervisor mode </strong></summary>

- [ ] sret
- [ ] sfence.vma
</details>

<details>
<summary><strong> A Extension Instructions（Low Piro）</strong></summary>

- [ ] lr.w
- [ ] lr.d
- [ ] sc.w
- [ ] sc.d
- [ ] amoswap.w
- [ ] amoswap.d
- [ ] amoadd.w
- [ ] amoadd.d
- [ ] amoxor.w
- [ ] amoxor.d
- [ ] amoand.w
- [ ] amoand.d
- [ ] amoor.w
- [ ] amoor.d
- [ ] amomin.w
- [ ] amomin.d
- [ ] amomax.w
- [ ] amomax.d
- [ ] amominu.w
- [ ] amominu.d
- [ ] amomaxu.w
- [ ] amomaxu.d
</details>

<details>
<summary><strong> M Extension Instructions（Low Piro）</strong></summary>

- [ ] mul
- [ ] mulh
- [ ] mulhsu
- [ ] mulhu
- [ ] mulw
- [ ] div
- [ ] divu
- [ ] rem
- [ ] remu
- [ ] divw
- [ ] divuw
- [ ] remw
- [ ] remuw
</details>

<details>
<summary><strong> System Tasks </strong></summary>

- [x] L1 Instruction cache
- [ ] L1 Data cache
- [ ] L2 Cache
- [ ] Exception & Interruption
- [ ] TLB
- [ ] MMU(SV-48)
- [ ] AXI Bus
</details>

<details>
<summary><strong> Causes Tasks </strong></summary>

- [ ] 0x00, misaligned fetch
- [ ] 0x01, fetch access
- [ ] 0x02, illegal instruction
- [ ] 0x03, breakpoint
- [ ] 0x04, misaligned load
- [ ] 0x05, load access
- [ ] 0x06, misaligned store
- [ ] 0x07, store access
- [ ] 0x08, user ecall
- [ ] 0x09, supervisor ecall
- [N/A] 0x0A, virtual supervisor ecall
- [ ] 0x0B, machine ecall
- [ ] 0x0C, fetch page fault
- [ ] 0x0D, load page fault
- [ ] 0x0F, store page fault
- [ ] 0x10, double trap
- [ ] 0x12, software check fault
- [ ] 0x13, hardware error fault
- [ ] 0x14, fetch guest page fault
- [ ] 0x15, load guest page fault
- [N/A] 0x16, virtual instruction
- [ ] 0x17, store guest page fault
</details>