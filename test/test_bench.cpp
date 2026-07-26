#include <verilated.h>
#include "VTop.h"
#include <iostream>

vluint64_t main_time = 0;

double sc_time_stamp() {
    return main_time;
}

int main(int argc, char** argv) {
    Verilated::commandArgs(argc, argv);
    Verilated::traceEverOn(true);

    VTop* top = new VTop;

    top->clock = 0;
    top->reset = 1;

    for (int i = 0; i < 10; i++) {
        top->clock = !top->clock;
        top->eval();
        main_time++;
    }
    top->reset = 0;

    for (int i = 0; i < 1000; i++) {
        top->clock = !top->clock;
        top->eval();
        main_time++;
    }

    std::cout << "Simulation completed: " << main_time << " ticks" << std::endl;

    delete top;
    return 0;
}
