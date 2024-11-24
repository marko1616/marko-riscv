## 🚀 RISC-V 和 HDL 学习项目

\[ [English](README.md) | 中文 \]

### 📂 项目结构
```
├── src
│   └── main
│       └── scala
│           └── markorv
│               ├── backend   # 执行单元
│               ├── cache     # 缓存
│               ├── frontend  # 前端
│               └── io        # 外部总线
└── tests
    ├── asmtst                # 汇编测试
    └── emulator              # 测试激励生成
```

### 程序顺序定义
*仅限暂时的程序顺序定义。* 不保证缓存写回顺序，但保证任意指令对 CPU 内部状态影响的顺序一致性（非乱序执行）。

### 更新路线
请参阅 [TODO.md](./TODO.md) 查看未来的更新计划。

### 架构与更新日志
在 [docs](./docs) 文件夹中了解详细的架构与更新日志。

### 使用说明
1. **克隆并初始化项目**
    ```bash
    git clone https://github.com/marko1616/marko-riscv.git
    make init
    ```

2. **安装构建工具**
    - *安装 mill 0.11.5*
    推荐执行下列命令
    ```bash
    curl -L https://github.com/com-lihaoyi/mill/releases/download/0.11.5/0.11.5 > mill && chmod +x mill
    export PATH="$(pwd):/$PATH" # 每次执行都需要添加。或者你可以修改你的bashrc等。
    ```

3. **执行 Makefile 选项**
    ```bash
    make gen-rom       # ⚙️ 生成十六进制 ROM
    make gen-tests     # ⚙️ 生成十六进制测试汇编程序
    make compile       # ⚙️ 编译 C++ 模拟程序
    ```

### 如何进行模拟
1. 修改 `tests/emulator/stimulus.cpp` 中的参数
2. 生成并运行模拟程序：
    ```bash
    make gen-rom
    make gen-tests
    make emu
    obj_dir/VMarkoRvCore --rom-path tests/emulator/assets/boot.bin --ram-path tests/asmtst/xxx.bin
    ```