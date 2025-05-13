# 🚀 RISC-V 与 HDL 学习项目

[ [English](README.md) | 中文 ]

本项目是一个专注于 RISC-V 架构和硬件描述语言（HDL）的学习项目，特别地，使用 Chisel 进行核心设计，使用 C++/Verilator 进行仿真。

### 🌟 特性
*   **RISC-V 核心**: 使用 Chisel 实现。
*   **仿真器**: 基于 C++ 的仿真器，使用 Verilator 处理 HDL 核心。
*   **汇编测试**: 一套 RISC-V 汇编测试程序。
*   **Docker化开发环境**: 使用 Docker 和 Docker Compose 轻松搭建一致的构建环境。
*   **Makefile 自动化**: 简化的构建和测试命令。

### 📂 项目结构

```
.
|-- Dockerfile              # 定义构建环境容器
|-- LICENSE
|-- Makefile                # 用于构建项目的主 Makefile
|-- README.md               # 英文版 README
|-- README_zh.md            # 本文件
|-- TODO.md
|-- build.mill              # Chisel 代码的 Mill 构建配置
|-- docker-compose.yml      # 用于便捷启动环境的 Docker Compose 文件
|-- docs
|   `-- update-log.md
|-- dump.bin
|-- emulator
|   |-- assets              # 引导加载程序、DTS 等
|   |   |-- Makefile        # 仿真器资源 (如 boot.bin) 的 Makefile
|   |   `-- ...
|   `-- src                 # C++ 仿真器源代码
|       `-- ...
|-- libs                    # 外部库 (例如 capstone, cxxopts 作为子模块或包含)
|   |-- capstone
|   `-- cxxopts
|-- src
|   `-- main
|       `-- scala
|           `-- markorv     # RISC-V 核心的 Chisel 源代码
|               |-- backend   # 执行单元
|               |-- bus       # 总线接口 (例如 AXI)
|               |-- cache     # 缓存实现
|               |-- config    # 核心配置
|               |-- frontend  # 取指、译码、发射阶段
|               |-- trap      # 异常处理
|               `-- utils     # 工具组件
|-- tests
|   |-- asmtst              # 核心的汇编测试
|   |   |-- general.ld
|   |   `-- src
|   |       `-- *.S         # 汇编源文件
|   |-- batched_test.py
|   `-- clock               # 时钟相关测试/工具
`-- ... (其他项目文件)
```

### 🛠️ 开发环境搭建

本项目使用 Docker化的开发环境以确保一致性和便捷性。

**先决条件:**
*   Docker Engine
*   Docker Compose
*   SSH 客户端
*   您的 SSH 公钥 (通常是 `~/.ssh/id_rsa.pub`)。如果您没有，请使用 `ssh-keygen -t rsa -b 4096` 生成。

**步骤:**

1.  **克隆仓库:**
    ```bash
    git clone https://github.com/marko1616/marko-riscv.git
    cd marko-riscv
    ```

2.  **为 Docker 准备 SSH 公钥:**
    Docker 环境使用您的 SSH 公钥以允许无密码 SSH 进入容器。
    复制您的公钥内容，下一步会用到。
    ```bash
    cat ~/.ssh/id_rsa.pub
    ```

3.  **启动开发环境:**
    构建并启动容器。在构建过程中，Docker 会使用您的公钥。
    ```bash
    # 如果您的公钥在变量中:
    # export MY_SSH_PUB_KEY=$(cat ~/.ssh/id_rsa.pub)
    # docker-compose build --build-arg SSH_PUB_KEY="$MY_SSH_PUB_KEY"
    # docker-compose up -d

    # 或者直接传递 (粘贴您的密钥或直接传递):
    docker-compose build --build-arg SSH_PUB_KEY="$(cat ~/.ssh/id_rsa.pub)"
    docker-compose up -d
    ```
    *   `build` 命令仅在首次运行或 `Dockerfile` 更改时需要。
    *   项目目录 (`.`) 被挂载到容器内的 `/home/build-user/code`。

4.  **连接到开发环境:**
    通过 SSH 连接到正在运行的容器。容器内的 SSH 服务器监听宿主机的 `8022` 端口。
    ```bash
    ssh build-user@localhost -p 8022
    ```
    您现在位于容器内的 `/home/build-user` 目录。您的项目代码位于 `/home/build-user/code`。

5.  **初始设置 (容器内):**
    导航到代码目录并执行初始设置：
    ```bash
    cd /home/build-user/code

    # 初始化并更新 Git 子模块 (例如 capstone)
    make init

    # 安装 mill (Scala 构建工具)
    # Dockerfile 已将 /home/build-user/code/ 添加到 PATH，因此将 mill 放在此处。
    # 此操作仅需执行一次。
    curl -L https://github.com/com-lihaoyi/mill/releases/download/0.12.5/0.12.5 > mill && chmod +x mill
    # 验证:
    # mill --version

    # 安装 RISC-V GNU 工具链 (如果您需要在容器内编译 .S 文件)
    # Makefile 需要 riscv64-unknown-elf-gcc。
    # 以下是在容器内安装它的示例说明。
    # 这是在容器内的一次性设置。
    echo "正在安装 RISC-V GNU 工具链..."
    sudo apt-get update
    sudo apt-get install autoconf automake autotools-dev curl \
                 python3 python3-pip python3-tomli libmpc-dev \
                 libmpfr-dev libgmp-dev gawk build-essential bison flex \ 
                 texinfo gperf libtool patchutils bc zlib1g-dev \ 
                 libexpat-dev ninja-build git cmake libglib2.0-dev libslirp-dev
    git clone https://github.com/riscv-collab/riscv-gnu-toolchain.git /tmp/riscv-gnu-toolchain
    cd /tmp/riscv-gnu-toolchain
    ./configure --prefix=/opt/riscv --with-arch=rv64ia_zicsr --with-abi=lp64
    sudo make -j$(nproc)
    cd /home/build-user/code
    echo 'export PATH="/opt/riscv/bin:$PATH"' >> ~/.bashrc
    source ~/.bashrc
    # 验证:
    # riscv64-unknown-elf-gcc --version
    ```
    *注意: Docker 容器中的 `start.sh` 脚本会在启动时运行 `make clean`。*

### 🏗️ 构建项目

所有构建命令都应在 **Docker 容器内** 的 `/home/build-user/code` 目录中运行。

*   **初始化/更新子模块并构建 Capstone:**
    ```bash
    make init
    ```
    (这也会构建仿真器所需的 Capstone 库。)

*   **从 Chisel 生成 Verilog 并编译 C++ 仿真器:**
    此命令首先使用 `mill` 从 Chisel 源文件生成 Verilog，然后使用 Verilator 编译 Verilog 以及 C++ 仿真器源文件。
    ```bash
    make compile
    ```
    仿真器可执行文件将是 `obj_dir/VMarkoRvCore`。

*   **生成汇编测试二进制文件:**
    将 `tests/asmtst/src/*.S` 中的汇编测试编译成 `.elf` 文件。
    ```bash
    make gen-tests
    ```
    输出的 ELF 文件将位于 `tests/asmtst/src/`。

*   **生成引导 ROM (用于仿真器):**
    构建仿真器所需的资源，如 `boot.elf`。
    ```bash
    make gen-rom
    ```
    输出将位于 `emulator/assets/`。

*   **清理构建产物:**
    ```bash
    make clean
    ```

### 🚀 运行仿真和测试

仿真在 **Docker 容器内** 运行。仿真器 `VMarkoRvCore` 直接加载 ELF 文件。

1.  **运行自定义汇编测试 (来自 `tests/asmtst`):**
    *   确保仿真器和引导加载程序已构建：
        ```bash
        make compile
        make gen-rom
        ```
    *   如果尚未编译，请编译您的特定汇编测试：
        ```bash
        make tests/asmtst/src/your_test_name.elf # 或 'make gen-tests' 编译所有
        ```
    *   使用您的自定义测试 ELF 运行仿真器：
        ```bash
        obj_dir/VMarkoRvCore --rom-path emulator/assets/boot.elf --ram-path tests/asmtst/src/your_test_name.elf
        ```
        将 `your_test_name.elf` 替换为所需的测试 (例如 `helloWorld.elf`)。查看 `obj_dir/VMarkoRvCore --help` 获取更多仿真器选项。

2.  **运行官方 RISC-V ISA 测试 (来自 `tests/riscv-tests`):**
    这些测试使用 `batched_test.py` 脚本运行。`riscv-tests` 子模块 (通过 `make init` 获取) 包含预编译的 ELF 文件。
    *   确保仿真器和引导加载程序已构建：
        ```bash
        make compile
        make gen-rom
        ```
    *   运行批量测试脚本：
        ```bash
        python3 tests/batched_test.py
        ```
        您可以指定并行任务的数量：
        ```bash
        python3 tests/batched_test.py -j $(nproc)
        ```
        该脚本将为 `riscv-tests/isa` 目录中的每个测试用例输出 PASSED/FAILED 状态。`Dockerfile` 已经安装了此脚本所需的 `python3-rich` 和 `python3-pyelftools`。

### 📜 程序顺序定义
*仅限暂时的程序顺序定义。* 不保证缓存写回顺序，但保证任意指令对 CPU 内部状态影响的顺序一致性（非乱序执行）。

### 🗺️ 更新路线
请参阅 [TODO.md](./TODO.md) 查看未来的更新计划。

### 🏛️ 架构与更新日志
在 [docs](./docs) 文件夹中了解详细的架构与更新日志。