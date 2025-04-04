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
<summary><strong> Fence </strong>✅</summary>

- [x] fence
</details>

<details>
<summary><strong> System Instructions </strong>✅</summary>

- [x] ecall
- [x] ebreak
- [x] mret
- [x] wfi
</details>

<details>
<summary><strong> Zicbom Extension Instructions </strong></summary>

- [ ] cbo.clean
- [ ] cbo.flush
- [ ] cbo.inval
</details>

<details>
<summary><strong> Zicboz Extension Instructions </strong></summary>

- [ ] cbo.zero
</details>

<details>
<summary><strong> Zifencei Extension Instructions </strong></summary>

- [ ] fence.i
</details>

<details>
<summary><strong> Zicsr Extension Instructions </strong>✅</summary>

- [x] csrrw
- [x] csrrs
- [x] csrrc
- [x] csrrwi
- [x] csrrsi
- [x] csrrci
</details>

<details>
<summary><strong> S Mode Instructions </strong></summary>

- [ ] sret
- [ ] sfence.vma
</details>

<details>
<summary><strong> A Extension Instructions </strong>✅</summary>

- [x] lr.w
- [x] lr.d
- [x] sc.w
- [x] sc.d
- [x] amoswap.w
- [x] amoswap.d
- [x] amoadd.w
- [x] amoadd.d
- [x] amoxor.w
- [x] amoxor.d
- [x] amoand.w
- [x] amoand.d
- [x] amoor.w
- [x] amoor.d
- [x] amomin.w
- [x] amomin.d
- [x] amomax.w
- [x] amomax.d
- [x] amominu.w
- [x] amominu.d
- [x] amomaxu.w
- [x] amomaxu.d
</details>

<details>
<summary><strong> M Extension Instructions </strong>✅</summary>

- [X] mul
- [X] mulh
- [X] mulhsu
- [X] mulhu
- [X] mulw
- [x] div
- [x] divu
- [x] rem
- [x] remu
- [x] divw
- [x] divuw
- [x] remw
- [x] remuw
</details>

<details>
<summary><strong> Zicntr Extension CSR</strong>✅</summary>

- [x] cycle
- [x] time
- [x] instret
</details>

<details>
<summary><strong> System Tasks </strong></summary>

- [ ] L1 Instruction cache
- [ ] L1 Data cache
- [ ] L2 Cache
- [x] Exception & Interruption
- [ ] TLB
- [ ] SV-48 MMU
- [x] AXI Bus
- [ ] Device tree
- [ ] Boot loader
</details>

<details>
<summary><strong> Causes Tasks </strong></summary>

- [ ] 0x00, misaligned fetch
- [ ] 0x01, fetch access
- [ ] 0x02, illegal instruction
- [ ] 0x03, breakpoint
- [N/A] 0x04, misaligned load
- [ ] 0x05, load access
- [N/A] 0x06, misaligned store
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