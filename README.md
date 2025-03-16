## 🚀 RISC-V and HDL Learning Project

\[ English | [中文](README_zh.md) \]

### 📂 Project Structure
```
├── src
│   └── main
│       └── scala
│           └── markorv
│               ├── backend   # Execution units
│               ├── cache     # Cache
│               ├── frontend  # Frontend
│               └── io        # External bus
└── tests
    ├── asmtst                # Assembly tests
    └── emulator              # Test stimulus generation
```

### Program Order Definition
*This is a temporary program order definition.* Write-back order in the cache is not guaranteed, but the consistency of instruction effects on the internal CPU state is ensured (non-out-of-order execution).

### Update Roadmap
Please refer to [TODO.md](./TODO.md) for future update plans.

### Architecture and Update Log
Check the [docs](./docs) folder for detailed architecture information and update logs.

### Usage Instructions
1. **Clone and init the project**
    ```bash
    git clone https://github.com/marko1616/marko-riscv.git
    make init
    ```

2. **Install build tools**
    - *Install mill 0.11.5*
    It is recommended to run the following commands:
    ```bash
    curl -L https://github.com/com-lihaoyi/mill/releases/download/0.11.5/0.11.5 > mill && chmod +x mill
    export PATH="$(pwd):/$PATH" # Needs to be added each time you run. Alternatively, modify your bashrc.
    ```

3. **Run Makefile options**
    ```bash
    make gen-rom           # ⚙️ Generate hex ROM image
    make gen-tests         # ⚙️ Generate hex test assembly programs
    make compile           # ⚙️ Compile C++ emulator
    ```

### How to Run Simulation
1. Modify the parameters in `tests/emulator/stimulus.cpp`
2. Generate and run the simulation:
    ```bash
    make gen-rom
    make gen-tests
    make emu
    obj_dir/VMarkoRvCore --rom-path tests/emulator/assets/boot.bin --ram-path tests/asmtst/xxx.bin
    ```

### How to get a fitted riscv-gnu-toolchain
```bash
sudo apt-get install autoconf automake autotools-dev curl python3 python3-pip python3-tomli libmpc-dev libmpfr-dev libgmp-dev gawk build-essential bison flex texinfo gperf libtool patchutils bc zlib1g-dev libexpat-dev ninja-build git cmake libglib2.0-dev libslirp-dev
git clone https://github.com/riscv-collab/riscv-gnu-toolchain.git
cd riscv-gnu-toolchain
./configure --prefix=/path/to/riscv-gnu-toolchain-build/ --with-arch=rv64ia_zicsr --with-abi=lp64
```