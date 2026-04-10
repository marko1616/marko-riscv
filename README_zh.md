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
├── Dockerfile                 # Docker 构建文件（构建开发与运行环境）
├── docker-compose.yml         # Docker Compose 配置文件（协调服务启动）
├── Makefile                   # 构建脚本统一入口（包含 build/test/clean 等任务）
├── build.mill                 # Mill 构建工具脚本（用于 Scala/Chisel）
├── cli.py                     # 交互式命令行工具（使用 Typer + Questionary + Rich）
│
├── README.md                  # 英文版说明文档
├── README_zh.md               # 中文版说明文档
├── LICENSE                    # 项目开源许可证
├── TODO.md                    # 待办事项列表和开发计划
│
├── docs/                      # 项目文档目录
│   └── update-log.md          # 更新日志
│
├── assets/                    # 核心配置资源
│   └── core_config.json       # 配置文件：核心模块参数
│
├── scripts/                   # 辅助脚本目录
│   └── batched_test.py        # 并行运行 RISC-V 汇编测试的脚本
│
├── tests/                     # 测试相关文件
│   ├── asmtests/              # 汇编测试源码及链接脚本
│   │   ├── general.ld
│   │   └── src/*.S
│   ├── clock/                 # 时钟模拟相关工具
│   └── riscv-tests/           # Git 子模块：RISC-V 官方 ISA 测试集
│
├── libs/                      # 外部依赖（Git 子模块）
│   ├── capstone/              # Capstone 反汇编引擎子模块（用于指令解析）
│   └── cxxopts/               # C++ 命令行参数解析库（用于仿真器 CLI）
│
├── emulator/                  # Verilator 驱动的测试平台（非完整仿真器）
│   ├── assets/                # ROM 构建相关文件（引导程序、设备树等）
│   └── src/                   # 测试平台 C++ 源码
│       ├── dpi/               # SystemVerilog DPI 接口头文件
│       └── slaves/            # 模拟外设（RAM、UART 等）
│
├── src/                       # RISC-V 处理器的 Chisel 源码
│   ├── main/scala/markorv/    # 主要处理器模块
│   │   ├── backend/           # 执行单元（ALU、乘除法等）
│   │   ├── frontend/          # 取指、译码、分支预测
│   │   ├── manage/            # 重命名/调度/寄存器文件管理
│   │   ├── bus/               # AXI 总线接口
│   │   ├── cache/             # 缓存子模块
│   │   ├── config/            # 配置模块
│   │   ├── trap/              # 异常/中断处理机制
│   │   └── utils/             # 工具函数
│   └── test/scala/markorv/    # Scala 编写的单元测试
│
├── project/
│   └── build.properties       # Mill 构建属性文件
└── mill/                      # Mill 相关工具目录
```

### 🛠️ 开发环境搭建

本项目使用 Docker 化的开发环境以确保一致性和便捷性。

**先决条件:**
*   Docker 引擎（18.09 或更高版本）
*   Docker Compose
*   SSH 客户端
*   您的 SSH 公钥（通常是 `~/.ssh/id_rsa.pub`）。如未生成，请使用命令 `ssh-keygen -t rsa -b 4096`。

**步骤:**

1.  **克隆项目仓库：**
    ```bash
    git clone https://github.com/marko1616/marko-riscv.git
    cd marko-riscv
    ```

2.  **准备 SSH 公钥作为 Secret：**
    Dockerfile 使用您的 SSH 公钥来允许无密码登录容器，请通过 BuildKit 的 secret mount 安全传入。

    > 首先确保启用了 BuildKit 构建支持：
    ```bash
    export DOCKER_BUILDKIT=1
    ```

3.  **构建 Docker 镜像：**
    使用如下命令构建镜像（请根据需要调整代理地址是否启用代理和路径等）：

    ```bash
    docker build \
        --build-arg USE_MIRROR=true \
        --build-arg PROXY="<your_proxy_url>" \
        --secret id=ssh_pub_key,src=~/.ssh/id_rsa.pub \
        -t your-dev-env-image .
    ```

4.  **启动开发环境：**
    使用 Docker Compose 启动容器：
    ```bash
    docker-compose up -d
    ```

5.  **连接开发环境：**
    使用 SSH 连接容器：
    ```bash
    ssh build-user@localhost -p 8022
    ```

    您现在已进入容器的 `/home/build-user` 目录，项目代码挂载在 `/home/build-user/code`。

6.  **容器内初始设置：**
    参见下方说明，在容器内执行相关开发依赖的安装与配置。

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

仿真在 **Docker 容器内** 运行，仿真器 `VMarkoRvCore` 可直接加载 ELF 文件。

#### 1. 运行自定义汇编测试（位于 `tests/asmtests/src`）：

    ```bash
    make build-simulator
    make build-sim-rom
    ```

    如需编译特定汇编测试：

    ```bash
    make tests/asmtests/src/your_test_name.elf   # 或使用 'make build-test-elves' 编译全部
    ```

    运行模拟器：

    ```bash
    obj_dir/VMarkoRvCore --rom-load elf:emulator/assets/boot.elf --ram-load elf:tests/asmtests/src/your_test_name.elf
    ```

    可用 `--help` 查看模拟器支持的全部参数。

#### 2. 运行 RISC-V 官方 ISA 测试（`tests/riscv-tests`）：

    这些测试使用脚本 `batched_test.py` 批量运行。运行前确保仿真器和 boot ROM 构建完成：

    ```bash
    make build-simulator
    make build-sim-rom
    ```

    运行脚本：

    ```bash
    python3 scripts/batched_test.py -j $(nproc)
    ```

    此脚本会自动测试 `riscv-tests/isa/` 中的所有可用的 ELF 文件，输出每个用例的 PASSED/FAILED 状态。

### 🛠️ Makefile 可用命令简表

| 命令名称                   | 功能描述                           |
| -------------------------- | ---------------------------------- |
| `make init`                | 初始化子模块，构建 Capstone        |
| `make build-simulator`     | 构建 RISC-V 仿真器                 |
| `make build-test-elves`    | 编译测试 ELF 文件                  |
| `make build-sim-rom`       | 构建仿真器使用的 ROM 文件          |
| `make clean-all`           | 清理所有构建产物                   |
| `make batched-riscv-tests` | 批量运行 RISC-V ISA 测试           |
| `make exit`                | 退出 CLI 工具（如果使用 CLI 管理） |

### 📜 内存顺序定义
*仅限暂时的内存顺序定义。* 不保证缓存写回顺序，但保证任意指令对 CPU 内部状态影响的顺序一致性（非乱序执行）。

### 🗺️ 更新路线
请参阅 [TODO.md](./TODO.md) 查看未来的更新计划。

### 🏛️ 架构与更新日志
在 [docs](./docs) 文件夹中了解详细的架构与更新日志。