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
|-- Dockerfile              # Defines the build environment container
|-- LICENSE
|-- Makefile                # Main Makefile for building the project
|-- README.md               # This file
|-- README_zh.md            # Chinese version of this README
|-- TODO.md
|-- build.mill              # Mill build configuration for Chisel code
|-- docker-compose.yml      # Docker Compose for easy environment startup
|-- docs
|   `-- update-log.md
|-- dump.bin
|-- emulator
|   |-- assets              # Bootloader, DTS, etc.
|   |   |-- Makefile        # Makefile for emulator assets (e.g., boot.bin)
|   |   `-- ...
|   `-- src                 # C++ emulator source code
|       `-- ...
|-- libs                    # External libraries (e.g., capstone, cxxopts as submodules or included)
|   |-- capstone
|   `-- cxxopts
|-- src
|   `-- main
|       `-- scala
|           `-- markorv     # Chisel source code for the RISC-V core
|               |-- backend   # Execution units
|               |-- bus       # Bus interfaces (e.g., AXI)
|               |-- cache     # Cache implementation
|               |-- config    # Core configuration
|               |-- frontend  # Fetch, decode, issue stages
|               |-- trap      # Trap handling
|               `-- utils     # Utility components
|-- tests
|   |-- asmtst              # Assembly tests for the core
|   |   |-- general.ld
|   |   `-- src
|   |       `-- *.S         # Assembly source files
|   |-- batched_test.py
|   `-- clock               # Clock-related tests/utils
`-- ... (other project files)
```

### 🛠️ Development Environment Setup

This project uses a Dockerized development environment to ensure consistency and ease of setup.

**Prerequisites:**
*   Docker Engine
*   Docker Compose
*   An SSH client
*   Your SSH public key (typically `~/.ssh/id_rsa.pub`). If you don't have one, generate it using `ssh-keygen -t rsa -b 4096`.

**Steps:**

1.  **Clone the Repository:**
    ```bash
    git clone https://github.com/marko1616/marko-riscv.git
    cd marko-riscv
    ```

2.  **Prepare SSH Public Key for Docker:**
    The Docker environment uses your SSH public key to allow passwordless SSH access into the container.
    Copy your public key content. We will use it in the next step.
    ```bash
    cat ~/.ssh/id_rsa.pub
    ```

3.  **Start the Development Environment:**
    Build and start the container. You'll be prompted to enter your SSH public key.
    ```bash
    # If your public key is in a variable:
    # export MY_SSH_PUB_KEY=$(cat ~/.ssh/id_rsa.pub)
    # docker-compose build --build-arg SSH_PUB_KEY="$MY_SSH_PUB_KEY"
    # docker-compose up -d

    # Or directly (paste your key when prompted or pass it directly):
    docker-compose build --build-arg SSH_PUB_KEY="$(cat ~/.ssh/id_rsa.pub)"
    docker-compose up -d
    ```
    *   The `build` command is only needed the first time or if `Dockerfile` changes.
    *   The project directory (`.`) is mounted into `/home/build-user/code` inside the container.

4.  **Connect to the Development Environment:**
    SSH into the running container. The SSH server inside the container listens on port `8022` of your host machine.
    ```bash
    ssh build-user@localhost -p 8022
    ```
    You are now inside the container, in the `/home/build-user` directory. Your project code is at `/home/build-user/code`.

5.  **Initial Setup (Inside the Container):**
    Navigate to the code directory and perform initial setup:
    ```bash
    cd /home/build-user/code

    # Initialize and update Git submodules (e.g., capstone)
    make init

    # Install mill (Scala build tool)
    # The Dockerfile adds /home/build-user/code/ to PATH, so place mill here.
    # This only needs to be done once.
    curl -L https://github.com/com-lihaoyi/mill/releases/download/0.12.5/0.12.5 > mill && chmod +x mill
    # Verify:
    # mill --version

    # Install RISC-V GNU Toolchain (if not already on your system path and you need to build .S files)
    # The Makefile expects riscv64-unknown-elf-gcc.
    # The following are example instructions for installing it within the container.
    # This is a one-time setup inside the container.
    echo "Installing RISC-V GNU Toolchain..."
    sudo apt-get update
    sudo apt-get install autoconf automake autotools-dev curl \
                 python3 python3-pip python3-tomli libmpc-dev \
                 libmpfr-dev libgmp-dev gawk build-essential bison flex \ 
                 texinfo gperf libtool patchutils bc zlib1g-dev \ 
                 libexpat-dev ninja-build git cmake libglib2.0-dev libslirp-dev
    git clone https://github.com/riscv-collab/riscv-gnu-toolchain.git /tmp/riscv-gnu-toolchain
    cd /tmp/riscv-gnu-toolchain
    ./configure --prefix=/opt/riscv --with-arch=rv64ima_zicsr --with-abi=lp64
    sudo make -j$(nproc)
    cd /home/build-user/code
    echo 'export PATH="/opt/riscv/bin:$PATH"' >> ~/.bashrc
    source ~/.bashrc
    # Verify:
    # riscv64-unknown-elf-gcc --version
    ```
    *Note: The `start.sh` script in the Docker container runs `make clean` on startup.*

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

1.  **Running Custom Assembly Tests (from `tests/asmtst`):**
    *   Ensure the emulator and bootloader are built:
        ```bash
        make compile
        make gen-rom
        ```
    *   Compile your specific assembly test if not already done:
        ```bash
        make tests/asmtst/src/your_test_name.elf # Or 'make gen-tests' for all
        ```
    *   Run the emulator with your custom test ELF:
        ```bash
        obj_dir/VMarkoRvCore --rom-path emulator/assets/boot.elf --ram-path tests/asmtst/src/your_test_name.elf
        ```
        Replace `your_test_name.elf` with the desired test (e.g., `helloWorld.elf`). Check `obj_dir/VMarkoRvCore --help` for more emulator options.

2.  **Running Official RISC-V ISA Tests (from `tests/riscv-tests`):**
    These tests are run using the `batched_test.py` script. The `riscv-tests` submodule (fetched by `make init`) contains pre-compiled ELF files.
    *   Ensure the emulator and bootloader are built:
        ```bash
        make compile
        make gen-rom
        ```
    *   Run the batch test script:
        ```bash
        python3 tests/batched_test.py
        ```
        You can specify the number of parallel jobs:
        ```bash
        python3 tests/batched_test.py -j $(nproc)
        ```
        The script will output PASSED/FAILED status for each test case from the `riscv-tests/isa` directory. The `Dockerfile` already installs `python3-rich` and `python3-pyelftools` required by this script.

### 📜 Program Order Definition
*This is a temporary program order definition.* Write-back order in the cache is not guaranteed, but the consistency of instruction effects on the internal CPU state is ensured (non-out-of-order execution).

### 🗺️ Update Roadmap
Please refer to [TODO.md](./TODO.md) for future update plans.

### 🏛️ Architecture and Update Log
Check the [docs](./docs) folder for detailed architecture information and update logs.