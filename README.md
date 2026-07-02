# 🚀 RISC-V and HDL Learning Project

[ English | [中文](README_zh.md) ]

This project is a learning-oriented RISC-V processor, HDL, simulation, and verification playground. The processor core is written in Chisel, simulated through Verilator with a C++ test platform, and gradually extended with architecture tests, debug tracing, MMU/TLB support, caches, and early synthesis/timing analysis flows.

## 🌟 Features

* **RISC-V processor core**: Implemented in Chisel, with frontend, backend, scheduling/rename, CSR, trap/interrupt, cache, and AXI bus components.
* **Pipelined multiply/divide unit**: Adds a fixed-latency MDU with a Booth Radix-4 multiplier, compressor tree, SRT divider, and quotient selection table.
* **Configurable math unit parameters**: Multiplier and divider pipeline behavior is controlled through `assets/core_config.yaml`.
* **MMU/TLB support**: Includes SV39 MMU support and configurable TLB entries for multiple page sizes.
* **Verilator simulation platform**: C++ test platform with ROM/ELF loading and virtual RAM, UART, CLINT, and PLIC devices.
* **Debug and tracing support**: DPI-based debug hooks expose internal core state during simulation.
* **Assembly and official tests**: Supports custom assembly tests, `riscv-tests`, and `riscv-arch-test`.
* **MDU stress test**: Adds `tests/asmtests/src/muldiv.S` to cover continuous MDU issue, dependencies, corner cases, and wrong-path squash behavior.
* **OpenSTA/Nangate45 timing exploration**: Adds scripts for synthesis and timing analysis using the NangateOpenCellLibrary from OpenROAD flow resources.
* **Dockerized development environment**: Uses Docker and Docker Compose for a consistent build environment.
* **Makefile and interactive CLI**: Provides entry points for common build, test, format, and timing-analysis tasks.

## 📂 Project Structure

```text
.
├── Dockerfile
├── docker-compose.yml
├── Makefile
├── cli.py
│
├── README.md
├── README_zh.md
├── LICENSE
├── TODO.md
│
├── docs/
│   └── update-log.md
│
├── assets/
│   ├── core_config.yaml        # Core parameters, including queues, register file, TLB, and MDU config
│   ├── abc.constr              # ABC mapping constraints
│   ├── abc.script              # ABC mapping script
│   ├── nangate45.sdc           # OpenSTA timing constraints
│   ├── synth_nangate45.ys      # Synlig/Yosys synthesis script
│   └── sta.tcl                 # OpenSTA analysis script
│
├── scripts/
│   ├── batched_test.py         # Batch runner for RISC-V test ELFs
│   └── gen_config.py           # Generates simulator config header from core_config.yaml
│
├── tests/
│   ├── asmtests/
│   │   ├── general.ld
│   │   └── src/
│   │       ├── *.S
│   │       └── muldiv.S        # MDU stress test
│   ├── riscv-arch-test/        # RISC-V architecture test submodule
│   └── riscv-tests/            # Official RISC-V ISA test submodule
│
├── libs/
│   ├── capstone/               # Disassembly engine
│   ├── cxxopts/                # C++ command-line option parser
│   └── OpenROAD-flow-scripts/  # Nangate45 liberty and OpenROAD-related resources
│
├── emulator/
│   ├── assets/                 # Boot ROM, device tree, and simulation binaries
│   └── src/
│       ├── debug/              # DPI debug and trace manager
│       └── slaves/             # Virtual RAM, UART, CLINT, PLIC, and other devices
│
└── core/
    ├── build.mill              # Scala/Chisel build script
    ├── generated/              # Generated Verilog/filelist and synthesis outputs
    └── src/
        ├── main/scala/markorv/
        │   ├── backend/        # ALU, BranchUnit, MDU, LSU, and other execution units
        │   ├── frontend/       # Fetch, decode, and branch prediction
        │   ├── manage/         # Scheduling, rename, commit, and register file logic
        │   ├── bus/            # AXI bus interfaces
        │   ├── cache/          # Instruction and data caches
        │   ├── config/         # Core configuration data structures
        │   ├── csr/            # Control and status registers
        │   ├── debug/          # Core debug export logic
        │   ├── math/           # Math building blocks
        │   │   ├── compressor/ # 3:2/4:2 compressors and compressor tree
        │   │   ├── divider/    # SRT divider and quotient selection table
        │   │   └── multiplier/ # Booth Radix-4 multiplier
        │   ├── trap/           # Trap, exception, and interrupt handling
        │   └── utils/          # Shared Chisel helpers
        └── test/scala/markorv/
```

## 🛠️ Development Environment Setup

This project is intended to be developed inside a Docker container to keep the build environment consistent.

### Prerequisites

* Docker Engine 18.09 or later
* Docker Compose
* SSH client
* SSH public key, usually located at `~/.ssh/id_rsa.pub`

If you do not have an SSH key yet, generate one with:

```bash
ssh-keygen -t rsa -b 4096
```

### Clone the repository

```bash
git clone https://github.com/marko1616/marko-riscv.git
cd marko-riscv
```

### Build the Docker image

The Dockerfile reads your SSH public key through a BuildKit secret so the container can be accessed without a password.

```bash
export DOCKER_BUILDKIT=1

docker build \
    --build-arg USE_MIRROR=true \
    --build-arg PROXY="<your_proxy_url>" \
    --secret id=ssh_pub_key,src=~/.ssh/id_rsa.pub \
    -t marko-riscv-dev .
```

If no proxy is needed, remove or leave the `PROXY` argument empty according to your local network setup.

### Start and enter the container

```bash
docker-compose up -d
ssh build-user@localhost -p 8022
```

Inside the container, the project is mounted at:

```bash
/home/build-user/code
```

All following commands assume this directory as the working directory.

## 🏗️ Building the Project

### Initialize submodules

```bash
make init
```

This initializes/updates Git submodules and builds Capstone for the simulator. Current submodules include `riscv-tests`, `riscv-arch-test`, `capstone`, `cxxopts`, and `OpenROAD-flow-scripts`.

### Generate Chisel Verilog

```bash
make build-core
```

This runs the Mill/Chisel build and emits Verilog plus filelists under `core/generated/`.

### Build the simulator

```bash
make build-simulator
```

This generates the core Verilog and compiles the C++ simulation platform through Verilator. The simulator executable is usually:

```bash
obj_dir/VMarkoRvCore
```

### Build the simulation ROM

```bash
make build-sim-rom
```

This builds the boot ROM, device tree, and other simulation assets under:

```bash
emulator/assets/
```

### Build custom assembly test ELFs

```bash
make build-test-elves
```

This compiles assembly tests from `tests/asmtests/src/*.S`.

To build a single test:

```bash
make tests/asmtests/src/muldiv.elf
```

### Clean build artifacts

```bash
make clean-all
```

## 🚀 Running Simulations and Tests

### Run a custom assembly test

First build the simulator and boot ROM:

```bash
make build-simulator
make build-sim-rom
```

Run a selected ELF:

```bash
obj_dir/VMarkoRvCore \
    --rom-load elf:emulator/assets/boot.elf \
    --ram-load elf:tests/asmtests/src/muldiv.elf
```

`muldiv.S` is the MDU stress test added in this update. It covers:

* Continuous independent multiply instructions
* Dependent multiply chains
* Mixed `mul/div/rem` execution
* Signed division overflow
* Division by zero and remainder by zero
* MDU instructions on wrong speculative paths
* Real MDU commits after branch recovery

A passing run prints through UART:

```text
MDU TEST PASS
```

### Run official RISC-V ISA tests

```bash
make build-simulator
make build-sim-rom
python3 scripts/batched_test.py -j $(nproc)
```

The script runs ELFs from `tests/riscv-tests/isa/` and reports `PASSED` or `FAILED` for each case.

## ⏱️ Synthesis and OpenSTA Timing Analysis

This update adds an early Nangate45-based synthesis and timing-analysis flow. It is intended for learning and quick timing exploration, not as a full backend signoff flow.

### Requirements

The following tools/resources are expected:

* `synlig`
* `sta`, provided by OpenSTA
* `libs/OpenROAD-flow-scripts/flow/platforms/nangate45/lib/NangateOpenCellLibrary_typical.lib`

Before running timing analysis:

```bash
make init
make build-core
```

### Run through the CLI

```bash
python3 cli.py
```

Select:

```text
opensta-timing
```

This task will:

1. Run `assets/synth_nangate45.ys` from `core/generated/`
2. Generate `core/generated/top_mapped.v`
3. Run OpenSTA with `assets/sta.tcl`
4. Report WNS, TNS, unconstrained paths, slew/cap/fanout violations, and power

### Run manually

```bash
make build-core

cd core/generated
synlig ../../assets/synth_nangate45.ys

cd ../..
sta assets/sta.tcl
```

## ⚙️ Core Configuration

Core parameters are stored in:

```bash
assets/core_config.yaml
```

MDU-related parameters added in this update include:

```yaml
mulCompTreeMaxStage: 2
dividerBase: 4
dividerRemLeadBits: 6
dividerDivisorLeadBits: 4
dividerMaxStage: 2
```

These parameters affect where pipeline registers are inserted in the Booth Radix-4 multiplier compressor tree and how many SRT divider iterations are grouped per pipeline stage.

TLB-related parameters include:

```yaml
tlb4KEntries: 32
tlb2MEntries: 8
tlb1GEntries: 4
```

## 🛠️ Common Commands

| Command                 | Description                              |
| ----------------------- | ---------------------------------------- |
| `make init`             | Initialize submodules and build Capstone |
| `make build-core`       | Generate Verilog from Chisel             |
| `make build-simulator`  | Build the Verilator simulator            |
| `make build-test-elves` | Build custom assembly test ELFs          |
| `make build-sim-rom`    | Build boot ROM and simulation assets     |
| `make clean-all`        | Clean build artifacts                    |
| `python3 cli.py`        | Start the interactive task menu          |

## 📜 Current Memory Ordering Note

The current implementation does not provide a global ordering guarantee for cache writeback order, but it does preserve the commit order of instruction effects visible to internal CPU state. This is a temporary definition and may change as cache coherency, out-of-order behavior, or bus modeling evolves.

## 🗺️ Roadmap

See [TODO.md](./TODO.md) for future development plans.

## 🏛️ Architecture and Update Log

See the [docs](./docs) directory for architecture notes and update logs.