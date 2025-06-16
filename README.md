# 🚀 RISC-V and HDL Learning Project

[ English | [中文](README_zh.md) ]

This project is a learning endeavor focused on RISC-V architecture and Hardware Description Languages (HDL), specifically using Chisel for core design and C++/Verilator for simulation.

### 🌟 Features
*   **RISC-V Core**: Implemented in Chisel.
*   **Emulator**: C++ based emulator using Verilator for the HDL core.
*   **Assembly Tests**: A suite of RISC-V assembly tests.
*   **Dockerized Development Environment**: Easy setup using Docker and Docker Compose for a consistent build environment.
*   **Makefile Automation**: Simplified build and test commands.

### 📂 Project Structure

```
.
├── Dockerfile                 # Dockerfile for building development/production environment
├── docker-compose.yml         # Compose configuration for service orchestration
├── Makefile                   # Project entry point for build/test/clean commands
├── build.mill                 # Mill build script for Scala/Chisel sources
├── cli.py                     # Interactive CLI (built with Typer + Questionary + Rich)
│
├── README.md                  # Main README in English
├── README_zh.md               # Translated README in Chinese
├── LICENSE                    # Project license file
├── TODO.md                    # Development roadmap and task list
│
├── docs/                      # Documentation directory
│   └── update-log.md          # Change history and updates
│
├── assets/                    # Core configuration assets
│   └── core_config.json       # Core-level configuration parameters
│
├── scripts/                   # Helper and automation scripts
│   └── batched_test.py        # Parallel RISC-V assembly test execution
│
├── tests/                     # Test resources
│   ├── asmtests/              # Assembly test sources and linker script
│   │   ├── general.ld
│   │   └── src/*.S
│   ├── clock/                 # Clock-related test helpers
│   └── riscv-tests/           # Git submodule: Official RISC-V ISA test suite
│
├── libs/                      # External dependencies (Git submodules)
│   ├── capstone/              # Capstone disassembly engine (used in emulator)
│   └── cxxopts/               # Lightweight C++ CLI options parser
│
├── emulator/                  # Verilator-based test platform (not a full C++ simulator)
│   ├── assets/                # Boot ROM, device tree, and other binaries
│   └── src/                   # C++ source code for the test harness
│       ├── dpi/               # SystemVerilog DPI-C header interfaces
│       └── slaves/            # Virtual peripherals (RAM, UART, etc.)
│
├── src/                       # Chisel source code for RISC-V processor
│   ├── main/scala/markorv/    # Core logic
│   │   ├── backend/           # Execution units (ALU, MUL/DIV, etc.)
│   │   ├── frontend/          # Fetch, decode, and branch prediction
│   │   ├── manage/            # Rename table, scheduler, register file
│   │   ├── bus/               # AXI bus interfaces
│   │   ├── cache/             # I/D cache modules
│   │   ├── config/            # Global parameters and configuration
│   │   ├── exception/         # Trap/exception handling
│   │   └── utils/             # Utility functions and helpers
│   └── test/scala/markorv/    # Scala-based unit tests
│
├── project/
│   └── build.properties       # Mill project metadata
└── mill/                      # Mill tool support files
```

### 🛠️ Development Environment Setup

This project uses a Dockerized development environment to ensure consistency and ease of setup.

**Prerequisites:**
*   Docker Engine (version 18.09 or later)
*   Docker Compose
*   An SSH client
*   Your SSH public key (typically `~/.ssh/id_rsa.pub`). If you don't have one, generate it using `ssh-keygen -t rsa -b 4096`.

**Steps:**

1.  **Clone the Repository:**
    ```bash
    git clone https://github.com/marko1616/marko-riscv.git
    cd marko-riscv
    ```

2.  **Prepare SSH Public Key as a Secret:**
    The Dockerfile uses your SSH public key to allow passwordless access into the container. Pass it securely using Docker BuildKit's secret mount.

    > Make sure BuildKit is enabled before building:
    ```bash
    export DOCKER_BUILDKIT=1
    ```

3.  **Build the Docker Image:**
    Run the following command to build the container image with your public key mounted securely:

    ```bash
        docker build \
        --build-arg USE_MIRROR=true \
        --build-arg PROXY="<your_proxy_url>" \
        --secret id=ssh_pub_key,src=~/.ssh/id_rsa.pub \
        -t your-dev-env-image .
    ```

4.  **Start the Container with Docker Compose:**
    ```bash
    docker-compose up -d
    ```

5.  **Access the Container via SSH:**
    ```bash
    ssh build-user@localhost -p 8022
    ```

    You are now inside the container at `/home/build-user`. Project files are located in `/home/build-user/code`.

6.  **Initial Setup (Inside the Container):**
    Follow the remaining setup instructions inside the container as described below.

### 🏗️ Building the Project

All build commands should be run **inside the Docker container** from the `/home/build-user/code` directory.

*   **Initialize/Update Submodules and Build Capstone:**
    ```bash
    make init
    ```
    (This also builds the Capstone library required by the emulator.)

*   **Generate Verilog from Chisel & Compile C++ Emulator:**
    This command first uses `mill` to generate Verilog from the Chisel sources, then uses Verilator to compile the Verilog along with the C++ emulator sources.
    ```bash
    make compile
    ```
    The emulator executable will be `obj_dir/VMarkoRvCore`.

*   **Generate Assembly Test Binaries:**
    Compiles assembly tests from `tests/asmtst/src/*.S` into `.elf`.
    ```bash
    make gen-tests
    ```
    Output ELFs will be in `tests/asmtst/src/`.

*   **Generate Boot ROM (for emulator):**
    Builds assets like `boot.elf` for the emulator.
    ```bash
    make gen-rom
    ```
    Output will be in `emulator/assets/`.

*   **Clean Build Artifacts:**
    ```bash
    make clean
    ```

### 🚀 Running Simulations and Tests

Simulations are run **inside the Docker container**. The emulator `VMarkoRvCore` loads ELF files directly.

#### 1. Running Custom Assembly Tests (from `tests/asmtests/src`):

    ```bash
    make build-simulator
    make build-sim-rom
    ```

    If you need to compile a specific assembly test:

    ```bash
    make tests/asmtests/src/your_test_name.elf   # Or use 'make build-test-elves' to compile all
    ```

    Run the emulator with your custom test ELF:

    ```bash
    obj_dir/VMarkoRvCore --rom-path emulator/assets/boot.elf --ram-path tests/asmtests/src/your_test_name.elf
    ```

    Use `--help` to view all available emulator options.

#### 2. Running Official RISC-V ISA Tests (from `tests/riscv-tests`):

    These tests are run using the `batched_test.py` script. Before running, make sure the emulator and boot ROM are built:

    ```bash
    make build-simulator
    make build-sim-rom
    ```

    Run the batch test script:

    ```bash
    python3 scripts/batched_test.py -j $(nproc)
    ```

    This script will automatically run all ELF files from the `riscv-tests/isa/` directory and output PASSED/FAILED status for each test.

### 🛠️ Available Makefile Commands Summary

| Command Name           | Description |
|------------------------|-------------|
| `make init`            | Initialize submodules and build Capstone |
| `make build-simulator` | Build the RISC-V emulator |
| `make build-test-elves`| Compile test ELF files |
| `make build-sim-rom`   | Build ROM files for the emulator |
| `make clean-all`       | Clean all build artifacts |
| `make batched-riscv-tests` | Run all RISC-V ISA tests in parallel |
| `make exit`            | Exit the CLI tool (if using CLI management) |

### 📜 Memory Order Definition
*This is a temporary Memory order definition.* Write-back order in the cache is not guaranteed, but the consistency of instruction effects on the internal CPU state is ensured (non-out-of-order execution).

### 🗺️ Update Roadmap
Please refer to [TODO.md](./TODO.md) for future update plans.

### 🏛️ Architecture and Update Log
Check the [docs](./docs) folder for detailed architecture information and update logs.