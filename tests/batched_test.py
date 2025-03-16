import os
import pathlib
import subprocess

from rich import print
from elftools.elf.elffile import ELFFile

BASE_PATH = pathlib.Path(__file__).parent.parent
TESTS_PATH = BASE_PATH / "tests" / "riscv-tests" / "isa"
EMULATOR_PATH = BASE_PATH / "obj_dir" / "VMarkoRvCore"
ROM_PATH = BASE_PATH / "emulator" / "assets" / "boot.elf"
RAM_DUMP_PATH = BASE_PATH / "tests" / "ram_dump.bin"

TEST_CASES = [
# I Extension
            "rv64ui-p-add",
            "rv64ui-p-addi",
            "rv64ui-p-addiw",
            "rv64ui-p-addw",
            "rv64ui-p-and",
            "rv64ui-p-andi",
            "rv64ui-p-auipc",
            "rv64ui-p-beq",
            "rv64ui-p-bge",
            "rv64ui-p-bgeu",
            "rv64ui-p-blt",
            "rv64ui-p-bltu",
            "rv64ui-p-bne",
            "rv64ui-p-jal",
            "rv64ui-p-jalr",
            "rv64ui-p-lb",
            "rv64ui-p-lbu",
            "rv64ui-p-ld",
            "rv64ui-p-ld_st",
            "rv64ui-p-lh",
            "rv64ui-p-lhu",
            "rv64ui-p-lui",
            "rv64ui-p-lw",
            "rv64ui-p-lwu",
            "rv64ui-p-ma_data",
            "rv64ui-p-or",
            "rv64ui-p-ori",
            "rv64ui-p-sb",
            "rv64ui-p-sd",
            "rv64ui-p-sh",
            "rv64ui-p-simple",
            "rv64ui-p-sll",
            "rv64ui-p-slli",
            "rv64ui-p-slliw",
            "rv64ui-p-sllw",
            "rv64ui-p-slt",
            "rv64ui-p-slti",
            "rv64ui-p-sltiu",
            "rv64ui-p-sltu",
            "rv64ui-p-sra",
            "rv64ui-p-srai",
            "rv64ui-p-sraiw",
            "rv64ui-p-sraw",
            "rv64ui-p-srl",
            "rv64ui-p-srli",
            "rv64ui-p-srliw",
            "rv64ui-p-srlw",
            "rv64ui-p-st_ld",
            "rv64ui-p-sub",
            "rv64ui-p-subw",
            "rv64ui-p-sw",
            "rv64ui-p-xor",
            "rv64ui-p-xori",
# Zifencei Extension
#           "rv64ui-p-fence_i",
# A Extension
            "rv64ua-p-amoadd_d",
            "rv64ua-p-amoadd_w",
            "rv64ua-p-amoand_d",
            "rv64ua-p-amoand_w",
            "rv64ua-p-amomax_d",
            "rv64ua-p-amomax_w",
            "rv64ua-p-amomaxu_d",
            "rv64ua-p-amomaxu_w",
            "rv64ua-p-amomin_d",
            "rv64ua-p-amomin_w",
            "rv64ua-p-amominu_d",
            "rv64ua-p-amominu_w",
            "rv64ua-p-amoor_d",
            "rv64ua-p-amoor_w",
            "rv64ua-p-amoswap_d",
            "rv64ua-p-amoswap_w",
            "rv64ua-p-amoxor_d",
            "rv64ua-p-amoxor_w",
            "rv64ua-p-lrsc",
]

passed_count = 0
failed_count = 0

for case in TEST_CASES:
    command = [
        str(EMULATOR_PATH),
        "--rom-path", str(ROM_PATH),
        "--ram-path", str(TESTS_PATH / case),
        "--max-clock", "8000",
        "--ram-dump", str(RAM_DUMP_PATH)
    ]
    result = subprocess.run(command, capture_output=True, text=True)
    if result.returncode != 0:
        print(f"[ERROR] while testing {case}")
        failed_count += 1
        continue
    tohost_offset = None
    with open(TESTS_PATH / case, 'rb') as file:
        elf = ELFFile(file)
        for section in elf.iter_sections():
            if section.name == ".tohost":
                tohost_offset = section['sh_addr'] - 0x80000000
    assert tohost_offset is not None
    with open(RAM_DUMP_PATH, "rb") as file:
        file.seek(tohost_offset)
        result = int.from_bytes(file.read(2), byteorder="little")
        if result == 1:
            print(f"[green][PASSED][/green] {case}")
            passed_count += 1
        else:
            print(f"[red][FAILED][/red] {case} at {result >> 1}")
            failed_count += 1
    os.remove(RAM_DUMP_PATH)

total_cases = len(TEST_CASES)
pass_rate = (passed_count / total_cases) * 100 if total_cases > 0 else 0

print("\n[bold cyan][STATISTICS][/bold cyan]")
print(f"[bold]Total cases:[/bold] {total_cases}")
print(f"[bold green]Passed:[/bold green] {passed_count}")
print(f"[bold red]Failed:[/bold red] {failed_count}")
print(f"[bold yellow]Pass rate:[/bold yellow] {pass_rate:.2f}%")