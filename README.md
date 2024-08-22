Overview
The WACC (Whiley Assembler and Compiler Course) language is a simple imperative language, and this compiler was developed to parse, analyze, and generate AT&T assembly code for WACC programs.

The compiler is built using Scala and features several stages, including lexical analysis, parsing, semantic analysis, and code generation.

Features
Lexical Analysis: Converts the raw source code into tokens for further processing.
Parsing: Transforms tokens into an abstract syntax tree (AST) representing the structure of the program.
Semantic Analysis: Ensures that the WACC program follows all semantic rules, including type checking and control flow validation.
Code Generation: Converts the validated AST into AT&T assembly code, ready for execution on ARM-based systems.
Error Handling: Provides meaningful error messages for syntax, semantic, and runtime errors.
