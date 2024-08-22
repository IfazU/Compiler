./valid/sequence/boolAssignment.wacc
calling the reference compiler on ./valid/sequence/boolAssignment.wacc
-- Test: boolAssignment.wacc

-- Uploaded file: 
---------------------------------------------------------------
# simple boolean variable declaration and assignment

# Output:

# Program:

begin
  bool b = false ;
  b = true
end
---------------------------------------------------------------

-- Compiler Output:
-- Compiling...
-- Printing Assembly...
boolAssignment.s contents are:
===========================================================
0	.globl main
1	.section .rodata
2	.text
3	main:
4		pushq %rbp
5		# pushq {%rbx, %r12}
6		subq $16, %rsp
7		movq %rbx, (%rsp)
8		movq %r12, 8(%rsp)
9		movq %rsp, %rbp
10		# Stack pointer unchanged, no stack allocated variables
11		movq $0, %rax
12		movq %rax, %r12
13		movq $1, %rax
14		movq %rax, %r12
15		# Stack pointer unchanged, no stack allocated variables
16		movq $0, %rax
17		# popq {%rbx, %r12}
18		movq (%rsp), %rbx
19		movq 8(%rsp), %r12
20		addq $16, %rsp
21		popq %rbp
22		ret
===========================================================
-- Finished

