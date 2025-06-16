FROM debian:trixie

ARG USE_MIRROR=false

# Proxy
ARG PROXY=""
ENV HTTP_PROXY=${PROXY}
ENV HTTPS_PROXY=${PROXY}
ENV ALL_PROXY=${PROXY}

RUN rm -f /etc/apt/sources.list.d/debian.sources

RUN if [ "$USE_MIRROR" = "true" ]; then \
        echo "Using Chinese mirror: ftp.cn.debian.org"; \
        echo "deb http://ftp.cn.debian.org/debian trixie main non-free-firmware non-free contrib" > /etc/apt/sources.list; \
        echo "deb http://ftp.cn.debian.org/debian-security trixie-security main non-free-firmware non-free contrib" >> /etc/apt/sources.list; \
        echo "deb http://ftp.cn.debian.org/debian trixie-updates main non-free-firmware non-free contrib" >> /etc/apt/sources.list; \
    else \
        echo "Using default Debian mirror"; \
        echo "deb http://deb.debian.org/debian trixie main non-free-firmware non-free contrib" > /etc/apt/sources.list; \
        echo "deb http://deb.debian.org/debian-security trixie-security main non-free-firmware non-free contrib" >> /etc/apt/sources.list; \
        echo "deb http://deb.debian.org/debian trixie-updates main non-free-firmware non-free contrib" >> /etc/apt/sources.list; \
    fi

# System tools
RUN apt update && apt full-upgrade -y

# System tools
RUN apt update && apt install -y \
    bash \
    sudo \
    ssh \
    wget \
    curl \
    autoconf \
    automake \
    autotools-dev

# Build tools
RUN apt update && apt install -y \
    gcc \
    g++ \
    clang \
    make \
    cmake \
    build-essential \
    gawk \
    bison \
    flex \
    texinfo \
    gperf \
    libtool \
    patchutils \
    bc \
    ninja-build

# Version control
RUN apt update && apt install -y \
    git

# Setup git global proxy
RUN if [ -n "${PROXY}" ]; then \
        git config --global http.proxy "${PROXY}" && \
        git config --global https.proxy "${PROXY}"; \
    else \
        echo "No proxy set, skipping git proxy configuration"; \
    fi

# Java environment
RUN apt update && apt install -y \
    openjdk-21-jdk

# Python packages
RUN apt update && apt install -y \
    python3-pip \
    python3-tomli \
    python3-rich \
    python3-questionary \
    python3-typer \
    python3-pyelftools \
    python3-jinja2

RUN useradd -m build-user && \
    echo "build-user ALL=(ALL) NOPASSWD:ALL" >> /etc/sudoers && \
    chsh -s /bin/bash build-user

# Setup user
RUN --mount=type=secret,id=ssh_pub_key \
    mkdir -p /home/build-user/.ssh && \
    chmod 700 /home/build-user/.ssh && \
    cp /run/secrets/ssh_pub_key /home/build-user/.ssh/authorized_keys && \
    chmod 600 /home/build-user/.ssh/authorized_keys && \
    chown -R build-user:build-user /home/build-user/.ssh

RUN mkdir -p /var/run/sshd && \
    sed -i 's/#PasswordAuthentication yes/PasswordAuthentication no/' /etc/ssh/sshd_config && \
    sed -i 's/#PermitRootLogin prohibit-password/PermitRootLogin no/' /etc/ssh/sshd_config && \
    echo "AllowUsers build-user" >> /etc/ssh/sshd_config

# Setup build-user
USER build-user
WORKDIR /home/build-user

# Install RISC-V GNU Compiler Toolchain
RUN sudo apt update && sudo apt install -y \
    libmpc-dev \
    libmpfr-dev \
    libgmp-dev \
    zlib1g-dev \
    libexpat-dev \
    libglib2.0-dev \
    libslirp-dev

RUN git clone https://github.com/riscv/riscv-gnu-toolchain.git

WORKDIR /home/build-user/riscv-gnu-toolchain

RUN ./configure --prefix=/home/build-user/riscv-gnu-toolchain-build --enable-multilib
RUN make -j $(nproc)

# Setup .bashrc && setup.sh
RUN echo "export PATH=\"/home/build-user/code/:/\$PATH\"" >> /home/build-user/.bashrc
RUN echo "export PATH=\"/home/build-user/riscv-gnu-toolchain-build/bin/:/\$PATH\"" >> /home/build-user/.bashrc
RUN echo "/usr/sbin/sshd -D" > /home/build-user/start.sh
RUN chmod +x /home/build-user/start.sh

USER root
EXPOSE 22
CMD ["sh", "-c" , "/home/build-user/start.sh"]
