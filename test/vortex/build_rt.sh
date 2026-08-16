#!/usr/bin/env bash
# Build the Vortex raytracer kernel and flatten it to a raw machine-code image.
#
# Uses the locally installed rustc for riscv32imafc-unknown-none-elf; `-c` and
# `-a` (compressed + atomics) are disabled so every emitted instruction is in
# the Vortex RTL's supported set. A custom linker script pins the layout to a
# flat, text-only image with _start at byte 0.

set -euo pipefail
cd "$(dirname "$0")"

RT_W="${RT_W:-40}"
RT_H="${RT_H:-40}"

# -zca (no 16-bit/compressed), -a (no atomics), -c legacy alias. The Vortex
# RTL config this kernel targets has EXT_C_ENABLE=false and EXT_A_ENABLE=false.
if [ -n "${RT_NOWSYNC:-}" ]; then CFG_NOWSYNC="--cfg RT_NOWSYNC"; else CFG_NOWSYNC=""; fi
RT_W="$RT_W" RT_H="$RT_H" rustc \
  --target riscv32imafc-unknown-none-elf \
  -C opt-level=2 \
  -C target-feature=-zca,-c,-a \
  -C debuginfo=0 \
  -C link-arg=-T -C link-arg=rt_balls.ld \
  $CFG_NOWSYNC \
  --edition=2021 \
  ./rt_balls.rs \
  -o rt_balls.elf

llvm-objcopy -O binary rt_balls.elf rt_balls.bin

echo "rt_balls.bin: $(stat -c%s rt_balls.bin) bytes (${RT_W}x${RT_H}, fb at 0x2000, stack at 0x3C00)"