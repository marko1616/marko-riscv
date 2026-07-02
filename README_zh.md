# 🚀 RISC-V 与 HDL 学习项目

[ [English](README.md) | 中文 ]

本项目是一个面向 RISC-V 处理器、硬件描述语言（HDL）与仿真验证的学习项目。处理器核心使用 Chisel 编写，通过 Verilator 与 C++ 测试平台运行仿真，并逐步补充架构测试、调试追踪、MMU/TLB、缓存以及简单综合/时序分析流程。

## 🌟 特性

* **RISC-V 处理器核心**：使用 Chisel 实现，包含前端、后端、调度/重命名、CSR、异常/中断、缓存、AXI 总线等模块。
* **流水线乘除法单元（MDU）**：引入固定延迟流水线 MDU，包含 Booth Radix-4 乘法器、压缩树、SRT 除法器与商选择表。
* **可配置数学单元参数**：通过 `assets/core_config.yaml` 配置乘法压缩树流水级、除法器基数、前导位宽与最大流水级。
* **MMU/TLB 支持**：包含 SV39 MMU 与多页大小 TLB 配置。
* **Verilator 仿真平台**：基于 C++ 的测试平台，支持加载 ROM/ELF，并提供 RAM、UART、CLINT、PLIC 等虚拟外设。
* **调试与追踪**：通过 DPI 驱动的调试接口导出核心内部状态，便于仿真观察与问题定位。
* **汇编与官方测试**：支持自定义汇编测试、`riscv-tests` 与 `riscv-arch-test` 子模块。
* **MDU 压力测试**：新增 `tests/asmtests/src/muldiv.S`，覆盖连续乘除法、数据相关、异常边界与错误路径冲刷。
* **OpenSTA/Nangate45 时序探索**：新增基于 NangateOpenCellLibrary 的综合与 OpenSTA 时序分析脚本。
* **Docker 化开发环境**：通过 Docker 和 Docker Compose 搭建一致的构建与运行环境。
* **Makefile 与交互式 CLI**：提供常用构建、测试、格式化与时序分析任务入口。

## 📂 项目结构

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
│   ├── core_config.yaml        # 核心配置，包括队列、寄存器堆、TLB 与 MDU 参数
│   ├── abc.constr              # ABC 映射约束
│   ├── abc.script              # ABC 映射脚本
│   ├── nangate45.sdc           # OpenSTA 时序约束
│   ├── synth_nangate45.ys      # Synlig/Yosys 综合脚本
│   └── sta.tcl                 # OpenSTA 分析脚本
│
├── scripts/
│   ├── batched_test.py         # 批量运行 RISC-V 测试 ELF
│   └── gen_config.py           # 根据 core_config.yaml 生成仿真配置头文件
│
├── tests/
│   ├── asmtests/
│   │   ├── general.ld
│   │   └── src/
│   │       ├── *.S
│   │       └── muldiv.S        # MDU 压力测试
│   ├── riscv-arch-test/        # RISC-V 架构测试子模块
│   └── riscv-tests/            # 官方 RISC-V ISA 测试子模块
│
├── libs/
│   ├── capstone/               # 反汇编引擎
│   ├── cxxopts/                # C++ 命令行参数解析库
│   └── OpenROAD-flow-scripts/  # Nangate45 liberty 与 OpenROAD 相关资源
│
├── emulator/
│   ├── assets/                 # boot ROM、设备树与仿真二进制资源
│   └── src/
│       ├── debug/              # DPI 调试/追踪管理器
│       └── slaves/             # RAM、UART、CLINT、PLIC 等虚拟外设
│
└── core/
    ├── build.mill              # Scala/Chisel 构建脚本
    ├── generated/              # 生成的 Verilog/filelist 与综合输出
    └── src/
        ├── main/scala/markorv/
        │   ├── backend/        # ALU、BranchUnit、MDU、LSU 等执行单元
        │   ├── frontend/       # 取指、译码与分支预测
        │   ├── manage/         # 调度、重命名、提交、寄存器堆
        │   ├── bus/            # AXI 总线接口
        │   ├── cache/          # 指令/数据缓存
        │   ├── config/         # 核心配置数据结构
        │   ├── csr/            # 控制与状态寄存器
        │   ├── debug/          # 核心调试导出
        │   ├── math/           # 数学基础模块
        │   │   ├── compressor/ # 3:2/4:2 压缩器与压缩树
        │   │   ├── divider/    # SRT 除法器与商选择表
        │   │   └── multiplier/ # Booth Radix-4 乘法器
        │   ├── trap/           # 陷阱、异常与中断处理
        │   └── utils/          # 通用 Chisel 工具函数
        └── test/scala/markorv/
```

## 🛠️ 开发环境搭建

本项目推荐在 Docker 容器中开发，以保证构建环境一致。

### 前置条件

* Docker Engine 18.09 或更高版本
* Docker Compose
* SSH 客户端
* SSH 公钥，通常位于 `~/.ssh/id_rsa.pub`

如果尚未生成 SSH 公钥，可执行：

```bash
ssh-keygen -t rsa -b 4096
```

### 克隆仓库

```bash
git clone https://github.com/marko1616/marko-riscv.git
cd marko-riscv
```

### 构建 Docker 镜像

Dockerfile 会通过 BuildKit secret 安全读取 SSH 公钥，用于容器内免密登录。

```bash
export DOCKER_BUILDKIT=1

docker build \
    --build-arg USE_MIRROR=true \
    --build-arg PROXY="<your_proxy_url>" \
    --secret id=ssh_pub_key,src=~/.ssh/id_rsa.pub \
    -t marko-riscv-dev .
```

如果不需要代理，可根据本地网络情况删除或留空 `PROXY` 参数。

### 启动并进入容器

```bash
docker-compose up -d
ssh build-user@localhost -p 8022
```

进入容器后，项目代码位于：

```bash
/home/build-user/code
```

后续命令默认在该目录执行。

## 🏗️ 构建项目

### 初始化子模块

```bash
make init
```

该命令用于初始化/更新 Git 子模块，并构建仿真器依赖的 Capstone。当前子模块包括 `riscv-tests`、`riscv-arch-test`、`capstone`、`cxxopts` 与 `OpenROAD-flow-scripts` 等。

### 生成 Chisel Verilog

```bash
make build-core
```

该命令使用 Mill 构建 Chisel 核心，并在 `core/generated/` 下生成 Verilog 与 filelist。

### 构建仿真器

```bash
make build-simulator
```

该命令会生成核心 Verilog，并使用 Verilator 编译 C++ 仿真平台。输出的仿真器可执行文件通常为：

```bash
obj_dir/VMarkoRvCore
```

### 构建仿真 ROM

```bash
make build-sim-rom
```

生成仿真所需的 boot ROM、设备树等资源，输出目录为：

```bash
emulator/assets/
```

### 构建自定义汇编测试 ELF

```bash
make build-test-elves
```

该命令会编译 `tests/asmtests/src/*.S` 下的汇编测试。

也可以单独构建某一个测试：

```bash
make tests/asmtests/src/muldiv.elf
```

### 清理构建产物

```bash
make clean-all
```

## 🚀 运行仿真与测试

### 运行自定义汇编测试

先确保仿真器与 boot ROM 已构建：

```bash
make build-simulator
make build-sim-rom
```

运行指定 ELF：

```bash
obj_dir/VMarkoRvCore \
    --rom-load elf:emulator/assets/boot.elf \
    --ram-load elf:tests/asmtests/src/muldiv.elf
```

`muldiv.S` 是本次更新中新增的 MDU 压力测试，覆盖内容包括：

* 连续独立乘法指令
* 相关乘法链
* 混合 `mul/div/rem`
* 有符号除法溢出
* 除零与取余除零
* 分支错误路径上的 MDU 指令冲刷
* 分支恢复后的真实 MDU 指令提交

测试通过时会通过 UART 输出类似：

```text
MDU TEST PASS
```

### 运行官方 RISC-V ISA 测试

```bash
make build-simulator
make build-sim-rom
python3 scripts/batched_test.py -j $(nproc)
```

脚本会批量运行 `tests/riscv-tests/isa/` 下的 ELF，并输出每个用例的 `PASSED` 或 `FAILED` 状态。

## ⏱️ 综合与 OpenSTA 时序分析

本次更新新增了基于 Nangate45 的早期综合/时序分析流程。该流程主要用于学习与快速时序探索，不等同于完整后端签核流程。

### 前置条件

需要可用的：

* `synlig`
* `sta`，即 OpenSTA
* `libs/OpenROAD-flow-scripts/flow/platforms/nangate45/lib/NangateOpenCellLibrary_typical.lib`

请先执行：

```bash
make init
make build-core
```

### 通过 CLI 运行

```bash
python3 cli.py
```

在交互菜单中选择：

```text
opensta-timing
```

该任务会：

1. 在 `core/generated/` 中运行 `assets/synth_nangate45.ys`
2. 生成 `core/generated/top_mapped.v`
3. 使用 `assets/sta.tcl` 运行 OpenSTA
4. 输出 WNS、TNS、unconstrained paths、slew/cap/fanout 违例与功耗报告

### 手动运行

```bash
make build-core

cd core/generated
synlig ../../assets/synth_nangate45.ys

cd ../..
sta assets/sta.tcl
```

## ⚙️ 核心配置

核心参数位于：

```bash
assets/core_config.yaml
```

与本次更新相关的 MDU 参数包括：

```yaml
mulCompTreeMaxStage: 2
dividerBase: 4
dividerRemLeadBits: 6
dividerDivisorLeadBits: 4
dividerMaxStage: 2
```

这些参数会影响 Booth Radix-4 乘法器压缩树插入流水寄存器的位置，以及 SRT 除法器每级包含的迭代数量。

TLB 相关参数包括：

```yaml
tlb4KEntries: 32
tlb2MEntries: 8
tlb1GEntries: 4
```

## 🛠️ 常用命令一览

| 命令                    | 描述                        |
| ----------------------- | --------------------------- |
| `make init`             | 初始化子模块并构建 Capstone |
| `make build-core`       | 从 Chisel 生成 Verilog      |
| `make build-simulator`  | 构建 Verilator 仿真器       |
| `make build-test-elves` | 构建自定义汇编测试 ELF      |
| `make build-sim-rom`    | 构建 boot ROM 与仿真资源    |
| `make clean-all`        | 清理构建产物                |
| `python3 cli.py`        | 启动交互式任务菜单          |

## 📜 当前内存序说明

当前实现不对缓存写回顺序作出全局有序保证，但保证指令对 CPU 内部可见状态的提交顺序一致性。该定义仍属于阶段性说明，后续可能随缓存一致性、乱序执行或总线模型变化而调整。

## 🗺️ 更新路线图

请参阅 [TODO.md](./TODO.md) 了解未来更新计划。

## 🏛️ 架构与更新日志

更多架构说明与变更记录请查看 [docs](./docs) 目录。