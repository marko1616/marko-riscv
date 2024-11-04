#include <iostream>
#include "markorv_core.h"

#define MAX_CLOCK 1024
#define DRAM_SIZE 4096

static uint8_t ram[DRAM_SIZE];
int main(int argc, char **argv, char **env)
{
    Verilated::commandArgs(argc, argv);

    uint64_t clock_cnt = 0;
    while (clock_cnt < MAX_CLOCK) {

        clock_cnt++;
    }
}