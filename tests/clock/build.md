```bash
riscv64-unknown-elf-gcc -march=rv64i_zicsr -mabi=lp64 -nostartfiles -mcmodel=medany -T ./time.ld -Os -c timestamp_to_iso8601.c -o timestamp_to_iso8601.o
riscv64-unknown-elf-gcc -march=rv64i_zicsr -mabi=lp64 -nostartfiles -mcmodel=medany -T ./time.ld -Os time.S ./timestamp_to_iso8601.o -o time.elf
```