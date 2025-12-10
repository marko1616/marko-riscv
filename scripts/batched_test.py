import os
import pathlib
import argparse
import subprocess
from concurrent.futures import ThreadPoolExecutor, as_completed

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
#           "rv64ui-p-ma_data", Don't support miss aligned data op yet
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
            "rv64ui-p-fence_i",
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
# M Extension
            "rv64um-p-mul",
            "rv64um-p-mulh",
            "rv64um-p-mulhsu",
            "rv64um-p-mulhu",
            "rv64um-p-mulw",
            "rv64um-p-div",
            "rv64um-p-divu",
            "rv64um-p-divuw",
            "rv64um-p-divw",
            "rv64um-p-rem",
            "rv64um-p-remu",
            "rv64um-p-remuw",
            "rv64um-p-remw",
]

parser = argparse.ArgumentParser()
parser.add_argument("-j", "--jobs", type=int, default=1, help="Number of parallel jobs")
args = parser.parse_args()
jobs = args.jobs

passed_count = 0
failed_count = 0

def run_test(case_name):
    tohost_offset = None
    tohost_addr = None
    with open(TESTS_PATH / case_name, 'rb') as file:
        elf = ELFFile(file)
        for section in elf.iter_sections():
            if section.name == ".tohost":
                tohost_addr = section['sh_addr']
                tohost_offset = tohost_addr - 0x80000000
                break

    ram_dump_path = BASE_PATH / "tests" / f"{case_name}.ram_dump.bin"
    command = [
        str(EMULATOR_PATH),
        "--rom-path", str(ROM_PATH),
        "--ram-path", str(TESTS_PATH / case_name),
        "--max-clock", "10000",
        "--ram-dump", str(ram_dump_path),
        "--cleanup-dcache", str(tohost_addr)
    ]
    result = subprocess.run(command, capture_output=True, text=True, stdin=subprocess.DEVNULL)
    if result.returncode != 0:
        return (False, case_name, None)

    if tohost_offset is None:
        return (False, case_name, None)

    with open(ram_dump_path, "rb") as file:
        file.seek(tohost_offset)
        result_value = int.from_bytes(file.read(2), byteorder="little")

    os.remove(ram_dump_path)

    if result_value == 1:
        return (True, case_name, None)
    else:
        return (False, case_name, result_value >> 1)

with ThreadPoolExecutor(max_workers=jobs) as executor:
    future_to_case = {executor.submit(run_test, case): case for case in TEST_CASES}

    for future in as_completed(future_to_case):
        passed, case_name, fail_at = future.result()
        if passed:
            print(f"[green][PASSED][/green] {case_name}")
            passed_count += 1
        else:
            if fail_at is not None:
                print(f"[red][FAILED][/red] {case_name} at {fail_at}")
            else:
                print(f"[ERROR] while testing {case_name}")
            failed_count += 1

total_cases = len(TEST_CASES)
pass_rate = (passed_count / total_cases) * 100 if total_cases > 0 else 0

print("\n[bold cyan][STATISTICS][/bold cyan]")
print(f"[bold]Total cases:[/bold] {total_cases}")
print(f"[bold green]Passed:[/bold green] {passed_count}")
print(f"[bold red]Failed:[/bold red] {failed_count}")
print(f"[bold yellow]Pass rate:[/bold yellow] {pass_rate:.2f}%")