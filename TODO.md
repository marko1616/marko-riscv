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

- [x] fence.i
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

- [x] sret
- [ ] sfence.vma
</details>

<details>
<summary><strong> A(Zalrsc + Zaamo) Extension Instructions </strong>✅</summary>

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
<summary><strong> C(Zca) Extension Instructions (RV64C, no FP) ✅</strong></summary>

- [x] c.addi4spn

- [x] c.lw
- [x] c.ld
- [x] c.sw
- [x] c.sd

- [x] c.nop
- [x] c.addi
- [x] c.addiw
- [x] c.li
- [x] c.lui
- [x] c.addi16sp

- [x] c.slli
- [x] c.srli
- [x] c.srai
- [x] c.andi

- [x] c.sub
- [x] c.xor
- [x] c.or
- [x] c.and
- [x] c.subw
- [x] c.addw

- [x] c.j
- [x] c.beqz
- [x] c.bnez

- [x] c.lwsp
- [x] c.ldsp
- [x] c.swsp
- [x] c.sdsp

- [x] c.jr
- [x] c.jalr
- [x] c.mv
- [x] c.add
- [x] c.ebreak

</details>

<details>
<summary><strong> Zicntr Extension CSR</strong>✅</summary>

- [x] cycle
- [x] time
- [x] instret
</details>

<details>
<summary><strong> Sstc Extension CSR</strong>✅</summary>

- [x] stimecmp
- [N/A] vstimecmp
- [x] menvcfg.STCE
- [N/A] henvcfg.STCE
</details>

<details>
<summary><strong> Svade/Svadu Extension</strong></summary>

- [ ] Svade
- [N/A] Svadu

</details>

<details>
<summary><strong> System Tasks </strong></summary>

- [x] L1 Instruction cache
- [x] L1 Data cache
- [ ] L2 Cache
- [x] Exception & Interruption
- [ ] TLB
- [x] SV-39 MMU
- [x] AXI Bus
- [x] Device tree
- [x] Boot loader
</details>

<details>
<summary><strong> Causes Tasks </strong></summary>

- [N/A] 0x00, misaligned fetch
- [x] 0x01, fetch access
- [x] 0x02, illegal instruction
- [x] 0x03, breakpoint
- [x] 0x04, misaligned load
- [x] 0x05, load access
- [x] 0x06, misaligned store
- [x] 0x07, store access
- [x] 0x08, user ecall
- [x] 0x09, supervisor ecall
- [N/A] 0x0A, virtual supervisor ecall
- [x] 0x0B, machine ecall
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