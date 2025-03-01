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

RUN apt-get update && apt-get install -y \
    bash \
    sudo \
    ssh \
    gcc \
    g++ \
    make \
    cmake \
    git \
    wget \
    curl \
    openjdk-21-jdk

RUN useradd -m build-user && \
    echo "build-user ALL=(ALL) NOPASSWD:ALL" >> /etc/sudoers && \
    chsh -s /bin/bash build-user

ARG SSH_PUB_KEY
RUN mkdir -p /home/build-user/.ssh && \
    echo "${SSH_PUB_KEY}" > /home/build-user/.ssh/authorized_keys && \
    chmod 700 /home/build-user/.ssh && \
    chmod 600 /home/build-user/.ssh/authorized_keys && \
    chown -R build-user:build-user /home/build-user/.ssh

RUN mkdir -p /var/run/sshd && \
    sed -i 's/#PasswordAuthentication yes/PasswordAuthentication no/' /etc/ssh/sshd_config && \
    sed -i 's/#PermitRootLogin prohibit-password/PermitRootLogin no/' /etc/ssh/sshd_config && \
    echo "AllowUsers build-user" >> /etc/ssh/sshd_config

# Ensure mill is in the path
RUN echo "export PATH=\"/home/build-user/code/:/\$PATH\"" >> /home/build-user/.bashrc
RUN echo "make -C /home/build-user/code clean && /usr/sbin/sshd -D" > /home/build-user/start.sh
RUN chmod +x /home/build-user/start.sh

EXPOSE 22
CMD ["sh", "-c" , "/home/build-user/start.sh"]
