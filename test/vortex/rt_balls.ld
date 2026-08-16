ENTRY(_start)

MEMORY
{
  HBM (rwx) : ORIGIN = 0x100, LENGTH = 0x4000
}

SECTIONS
{
  .text : {
    KEEP(*(.text.start))
    *(.text .text.*)
  } > HBM

  .rodata : {
    *(.rodata .rodata.*)
  } > HBM

  .data : {
    *(.data .data.*)
    *(.sdata*)
  } > HBM

  .bss (NOLOAD) : {
    *(.bss .bss.*)
    *(.sbss*)
  } > HBM

  /DISCARD/ : {
    *(.riscv.attributes)
    *(.comment)
    *(.note*)
    *(.eh_frame)
    *(.eh_frame_hdr)
    *(.debug*)
    *(.llvm_addrsig)
    *(.sdata2 .sbss2)
  }
}