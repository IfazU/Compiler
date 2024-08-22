./valid/variables/intDeclaration.wacc
calling the reference compiler on ./valid/variables/intDeclaration.wacc
-- Test: intDeclaration.wacc

-- Uploaded file: 
---------------------------------------------------------------
# simple integer variable declaration

# Output:

# Program:

begin
  int x = 42
end
---------------------------------------------------------------

-- Compiler Output:
-- Compiling...
-- Printing Assembly...
intDeclaration.s contents are:
===========================================================
0	.data
1	.align 4
2	.text
3	.global main
4	main:
5		push {fp, lr}
6		push {r4, r8, r10, r12}
7		mov fp, sp
8		@ Stack pointer unchanged, no stack allocated variables
9		mov r8, #42
10		mov r4, r8
11		@ Stack pointer unchanged, no stack allocated variables
12		mov r0, #0
13		pop {r4, r8, r10, r12}
14		pop {fp, pc}
===========================================================
-- Finished

