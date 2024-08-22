package wacc

import semanticChecker.SemanticInfo
import IR._
import scala.collection.mutable.ListBuffer
import ast._
import scala.collection.mutable.HashMap
import implicits._
import semAst._
import scala.collection.mutable.HashSet
import scala.collection.mutable.Stack
import globals._
import utils._


/*
 * This function takes in the AST, function and symbol table and generates the IR code
 * 
 * @param semanticInfo SemanticInfo contains the AST, function and symbol table
 * codeGenerator is only used when semantic check is successful, so SemanticInfo is always present
 * @param targetConfig The configuration for the specified architecture
 * @return The IR code
 */
 
class Translator(val semanticInfo: SemanticInfo, val targetConfig: TargetConfig) {
    import targetConfig._

    // Class that represents the list of instructions per function label and associated string labels with it
    case class LabelInfo(buf: ListBuffer[Line], readonlys: HashSet[StringLabel])

    object LabelInfo {
        def empty: LabelInfo = LabelInfo(ListBuffer.empty, HashSet.empty)
        def apply(buf: ListBuffer[Line]): LabelInfo = new LabelInfo(buf, HashSet.empty)
    }

    // Stack data structure that store the location of where the next variable 
    // will be stored relative to base pointer
    var stackOffsets: Stack[Int] = Stack.empty
    // Counter to distinguish between different labels -> todo: maybe use a set or something else
    var branchCounter: Int = 0
    // Map which contains all the function labels and their instructions
    var funcMap: HashMap[FuncLabel, LabelInfo] = HashMap.empty

    /**
      * Function that flattens the funcMap into a list of instructions
      */
    def flattenToAssembly(): List[Line] = {
        var asmList: ListBuffer[Line] = ListBuffer.empty

        asmList += GlobalTag

        // Helper function to group ASM output into Main, Wacc functions, Wrapper functions, Errors respectively
        def groupFunc(funcLabelPair: (FuncLabel, LabelInfo)): Int = funcLabelPair._1 match {
            case MainLabel => 0
            case WaccFuncLabel(_name) => 1
            case _: WrapperFuncLabel => 2
            case _: ErrorFuncLabel => 3
            case _ => -1
        }

        // Set that holds already defined strings so there are no repeats
        var stringSet: HashSet[StringLabel] = HashSet.empty

        asmList ++= funcMap.toList.sortBy(groupFunc).flatMap{ case (func, LabelInfo(body, readonlys)) =>
            val buf: ListBuffer[Line] = ListBuffer.empty 
            
            if (!readonlys.isEmpty) {
                buf += ReadonlyTag
                readonlys.foreach { ro =>
                    if (!stringSet.contains(ro)) {
                        stringSet.addOne(ro)
                        buf += ro
                    }
                }
                buf += TextTag
            } else if (func == MainLabel) {
                buf += TextTag
            }

            buf += func
            buf ++= body
            buf += EmptyLine
            buf
        }

        asmList.toList
    }

    /**
      * Function that translate the ast into assembly
      */
    def translate(): List[Line] = {
        translateProg(semanticInfo.ast)
        flattenToAssembly()
    }

    
    def translateProg(prog: Prog) = {
        funcMap.addOne((MainLabel, translateMain(prog)))
        prog.funcs.foreach(f => funcMap.addOne((WaccFuncLabel(f.name), translateFunction(f))))
    }

    /**
      * Generates code for main scope of program
      */
    def translateMain(prog: Prog)(implicit currentLabel: LabelInfo = LabelInfo.empty): LabelInfo = {
        currentLabel.buf += PushASM(BasePointer)
        currentLabel.buf += MovASM(StackPointer, BasePointer)

        translateBlock(prog.stats)(currentLabel, prog.scope)

        currentLabel.buf += MovASM(DefaultExitCode, ReturnReg)
        currentLabel.buf += PopASM(BasePointer)
        currentLabel.buf += RetASM

        currentLabel
    }

    /**
      * Allocation variables on the stack by subtracting scope size from Stack Pointer
      */
    def allocateStackVariables()(implicit currentLabel: LabelInfo, st: SymbolTable): Unit = {
        stackOffsets.push(0)
        if (st.getScopeSize() > 0) {
            currentLabel.buf += Comment(s"The current scope size is ${st.getScopeSize()}")
            currentLabel.buf += SubASM(Imm(st.getScopeSize()), StackPointer, StackPointer)
        }
    }

    /**
      * Deallocation of variables on the stack by adding scope size to Stack Pointer
      */
    def popStackVariables()(implicit currentLabel: LabelInfo, st: SymbolTable): Unit = {
        stackOffsets.pop()
        if (st.getScopeSize() > 0) {
            currentLabel.buf += AddASM(Imm(st.getScopeSize()), StackPointer, StackPointer)
        }
    }

    /**
      * Generates code for statements
      */
    def translateStatement(stat: Stat)(implicit currentLabel: LabelInfo, st: SymbolTable): Unit = {
        stat match {
            case node: If         => translateIf(node)
            case node: Assign     => translateAssign(node)
            case node: AssignNew  => translateDeclaration(node)
            case node: Read       => translateRead(node)
            case node: Print      => translatePrint(node)
            case node: Println    => translatePrintln(node)
            case node: CallStat   => translateCall(node)
            case node: Skip       => 
            case node: LoopControl => translateLoopControl(node)
            case node: Loop       => translateLoop(node)
            case node: Exit       => translateExit(node)
            case node: Scope      => translateBlock(node.stats)(currentLabel, node.enclosingScopes(0))
            case node: Free       => translateFree(node)
            case node: Return     => translateReturn(node)
        }
    }

    def translateLoopControl(node: LoopControl)(implicit currentLabel: LabelInfo, st: SymbolTable): Unit = {
        node match {
            case _: Break => currentLabel.buf += JmpASM(node.enclosingLoop.doneLabel)
            case _: Continue => currentLabel.buf += JmpASM(node.enclosingLoop.updateLabel)
        }
    }
    
    def generateBranchLabels(node: Node): Unit = {
        node match {
            case loop: Loop =>
                val loopType = loop match {
                    case _: Do => "do"
                    case _: For => "for"
                    case _: While => "while"
                }

                loop.doneLabel = JumpLabel(s".L_${loopType}_done_$branchCounter")
                loop.repeatLabel = JumpLabel(s".L_${loopType}_repeat_$branchCounter")
                loop.condLabel = JumpLabel(s".L_${loopType}_cond_$branchCounter")

                loop.updateLabel = loop match {
                    case _: For => JumpLabel(s".L_${loopType}_update_$branchCounter")
                    case _ => loop.condLabel
                }

                branchCounter += 1
            case other => System.err.println(s"Warning: No labels were generated for $other")
        }
    }

    def translateLoop(node: Loop)(implicit currentLabel: LabelInfo, st: SymbolTable): Unit = {
        generateBranchLabels(node)

        currentLabel.buf += Comment("Begin LOOP")

        node match {
            case _: While =>
                currentLabel.buf += JmpASM(node.condLabel)
            case forStat: For =>
                translateStatement(forStat.start)
                currentLabel.buf += JmpASM(node.condLabel)
            case _ =>
        }
        
        currentLabel.buf += node.repeatLabel
        translateBlock(node.stats)(currentLabel, node.enclosingScopes(0))

        node match {
            case forStat: For =>
                currentLabel.buf += node.updateLabel
                translateStatement(forStat.update)
            case _ =>
        }

        currentLabel.buf += node.condLabel

        translateExpression(node.cond)
        currentLabel.buf += CmpASM(TrueImm, ScratchRegs.head, Byte)
        currentLabel.buf += JmpASM(node.repeatLabel, Equal)

        currentLabel.buf += node.doneLabel

        currentLabel.buf += Comment("End LOOP")
    }

    /**
      * Generates code for 'if' statements
      */
    def translateIf(node: If)(implicit currentLabel: LabelInfo, st: SymbolTable): Unit = {
        // Implement translation for If statement here
        val trueLabel = JumpLabel(s".L_if_true_$branchCounter")
        val endLabel = JumpLabel(s".L_if_end_$branchCounter")
        branchCounter += 1

        currentLabel.buf += Comment("Begin IF")


        translateExpression(node.cond)
        currentLabel.buf += CmpASM(TrueImm, ScratchRegs.head)
        currentLabel.buf += JmpASM(trueLabel, Equal)
        translateBlock(node.elseStat)(currentLabel, node.enclosingScopes(1))
        currentLabel.buf += JmpASM(endLabel)
        currentLabel.buf += trueLabel
        translateBlock(node.ifStat)(currentLabel, node.enclosingScopes(0))
        currentLabel.buf += endLabel

        currentLabel.buf += Comment("End IF")
        
    }

    def translateAssign(node: Assign)(implicit currentLabel: LabelInfo, st: SymbolTable): Unit = {
        // Implement translation for Assign statement here
        currentLabel.buf += Comment(s"Begin Assign $node")
        translateRValue(node.rvalue)
        translateLValue(node.lvalue, true)
        currentLabel.buf += Comment(s"End Assign")
    }

    def translateDeclaration(node: AssignNew)(implicit currentLabel: LabelInfo, st: SymbolTable): Unit = {
        // Implement translation for Declaration statement here
        currentLabel.buf += Comment(s"Begin Declaration $node")
        translateRValue(node.rvalue)
        assignLocation(node)
        currentLabel.buf += Comment(s"End Declaration")
    }

    /**
      * Assigns the next location of a variable declaration into memory by checking relative to
      * base pointer and stackOffsets
      */
    def assignLocation(node: AssignNew)(implicit currentLabel: LabelInfo, st: SymbolTable): Unit = {
        // Implement translation for Declaration statement here
        val memLocation = Memory(BasePointer, stackOffsets.head - st.getBasePointerOffset())

        val size = semanticToSize(syntaxToSemanticType(node.declType))

        st.updateLocation(node.name, memLocation)
        incrementStackOffset(sizeToInt(size))

        currentLabel.buf += MovASM(ScratchRegs.head, memLocation, size)
    }

    /**
      * Similar to above but parameters don't update their location in symbol table, 
      */
    def assignParam(node: Param)(implicit currentLabel: LabelInfo, st: SymbolTable): Unit = {
        val size = semanticToSize(syntaxToSemanticType(node.declType))

        val localMemLocation = Memory(StackPointer,  stackOffsets.head)
        
        incrementStackOffset(sizeToInt(size))
        currentLabel.buf += MovASM(ScratchRegs.head, localMemLocation, size)
    }

    def incrementStackOffset(size: Int) = {
        assert(!stackOffsets.isEmpty, "Stack offsets should not be empty")
        stackOffsets.push(stackOffsets.pop() + size)
    }

    /**
      * Generates code for 'read'
      */
    def translateRead(node: Read)(implicit currentLabel: LabelInfo, st: SymbolTable): Unit = {
        // Implement translation for Read statement here
        val (wrapperLabel, fstring) = (node.enclosingType: @unchecked) match {
            case SemChar => (ReadCharLabel, " %c")
            case SemInt => (ReadIntLabel, "%d")
        }

        if (!funcMap.contains(wrapperLabel)) {
            funcMap.addOne(wrapperLabel, translateReadLabel(wrapperLabel, fstring, semanticToSize(node.enclosingType)))
        }

        // get the lvalue
        translateLValue(node.lvalue)
        currentLabel.buf += MovASM(ScratchRegs.head, ParamRegs.head)
        currentLabel.buf += CallASM(wrapperLabel)
        translateLValue(node.lvalue, true)
    }

    /**
      * Generates code for 'print'
      */
    def translatePrint(node: Printable)(implicit currentLabel: LabelInfo, st: SymbolTable): Unit = {
        // Implement translation for Print statement here
        translateExpression(node.expr)
        currentLabel.buf += MovASM(ScratchRegs.head, ParamRegs.head)
        val (wrapperLabel, fstring) = (node.enclosingType: @unchecked) match {
            case SemBool => (PrintBoolLabel, "%.*s")
            case SemInt => (PrintIntLabel, "%d")
            case SemChar => (PrintCharLabel, "%c")
            case SemString | SemArray(SemChar) => (PrintStrLabel, "%.*s")

            case other => (PrintPtrLabel, "%p")
        }

        if (!funcMap.contains(wrapperLabel)) {
            funcMap.addOne(wrapperLabel, translatePrintLabel(wrapperLabel, fstring, node.enclosingType))
        }

        currentLabel.buf += CallASM(wrapperLabel)

    }

    /**
      * Generates code for 'println' by calling translatePrint() and adding code for a new line
      */
    def translatePrintln(node: Println)(implicit currentLabel: LabelInfo, st: SymbolTable): Unit = {
        // Implement translation for Println statement here
        translatePrint(node)

        if (!funcMap.contains(PrintlnLabel)) {
            funcMap.addOne((PrintlnLabel, translatePrintlnLabel))
        }

        currentLabel.buf += CallASM(PrintlnLabel)
    }

    /**
      * Generates code for 'exit'
      */
    def translateExit(node: Exit)(implicit currentLabel: LabelInfo, st: SymbolTable): Unit = {
        // Implement translation for Exit statement here
        if (!funcMap.contains(ExitWrapperLabel)) {
            funcMap.addOne(ExitWrapperLabel, translateExitLabel)
        }

        translateExpression(node.expr)
        currentLabel.buf += MovASM(ReturnReg, ParamRegs.head)
        currentLabel.buf += CallASM(ExitWrapperLabel)
    }

    /**
      * Translates code block in scopes such as 'if' and 'while'
      */
    def translateBlock(stats: List[Stat])(implicit currentLabel: LabelInfo, st: SymbolTable): Unit = {
        allocateStackVariables()
        stats.foreach(translateStatement(_))
        popStackVariables()
    }

    /**
      * Generates code for freeing of arrays or pairs
      */
    def translateFree(node: Free)(implicit currentLabel: LabelInfo, st: SymbolTable): Unit = {
        // Implement translation for Free statement here
        val freeLabel = if (node.isArray) FreeArrayLabel else FreePairLabel
        
        if (!funcMap.contains(freeLabel)) {
                funcMap.addOne(freeLabel, translateFreeLabel(node.isArray))
        }

        translateExpression(node.expr)

        if (node.isArray) {
            currentLabel.buf += Comment(s"array pointers are shifted forward by ${sizeToInt(DWord)} bytes, so correct it back to original pointer before free")
            currentLabel.buf += SubASM(Imm(sizeToInt(DWord)), ScratchRegs.head, ScratchRegs.head)
        }

        currentLabel.buf += MovASM(ScratchRegs.head, ParamRegs.head)
        currentLabel.buf += CallASM(freeLabel)
    }

    /**
      * Generates code for 'return' statements in functions
      */
    def translateReturn(node: Return)(implicit currentLabel: LabelInfo, st: SymbolTable): Unit = {
        // Implement translation for Return statement here
        node.exprOption match {
            case None => // void returns
            case Some(expr) => translateExpression(expr)
        }
        currentLabel.buf += MovASM(BasePointer, StackPointer)
        currentLabel.buf += PopASM(BasePointer)
        currentLabel.buf += RetASM
    }

    /**
      * Generates code for expressions and atoms
      */
    def translateExpression(expr: Expr)(implicit currentLabel: LabelInfo, st: SymbolTable): Unit = {
        expr match {
            case v: Var => translateVar(v)
            case v: ArrayVal => translateArrayElem(v)

            case node: BinOp => translateBinOp(node)
            case node: UnOp => translateUnOp(node)
            case node: TernaryOp => translateTernaryOp(node)
            
            case CharVal(c) => currentLabel.buf += MovASM(Imm(c.toInt), ScratchRegs.head)
            case BoolVal(b) => currentLabel.buf += MovASM(Imm(if (b) 1 else 0), ScratchRegs.head)
            case PairVal() => currentLabel.buf += MovASM(NullImm, ScratchRegs.head)
            case IntVal(x) => currentLabel.buf += MovASM(Imm(x), ScratchRegs.head)
            case StrVal(s) =>
                val stringLabel = addStringConstant(s)
                currentLabel.buf += LeaASM(Memory(InstructionPointer, stringLabel), ScratchRegs.head)
        }
    }

    def translateTernaryOp(ternop: TernaryOp)(implicit currentLabel: LabelInfo, st: SymbolTable): Unit = {
        ternop match {
            case IfExpr(cond, ifExpr, elseExpr) => 
                val trueLabel = JumpLabel(s".L_if_expr_true_$branchCounter")
                val endLabel = JumpLabel(s".L_if_expr_end_$branchCounter")
                branchCounter += 1

                translateExpression(cond)
                currentLabel.buf += CmpASM(TrueImm, ScratchRegs.head)
                currentLabel.buf += JmpASM(trueLabel, Equal)
                translateExpression(elseExpr)
                currentLabel.buf += JmpASM(endLabel)
                currentLabel.buf += trueLabel
                translateExpression(ifExpr)
                currentLabel.buf += endLabel
        }
    }

    /**
      * Generates code for arithmetic and logical binary operations and comparisons
      */
    def translateBinOp(binop: BinOp)(implicit currentLabel: LabelInfo, st: SymbolTable): Unit = {
        currentLabel.buf += Comment(s"Begin expression $binop")
        binop match {
            case op@ArithmeticOp(lhs, rhs) =>
                translateExpression(rhs)
                currentLabel.buf += PushASM(ScratchRegs.head)
                translateExpression(lhs)
                currentLabel.buf += PopASM(ScratchRegs(1))

                op match {
                    case divMod @ (Mod(_, _) | Div(_, _)) =>
                        if (!funcMap.contains(CheckDivZeroLabel)) {
                            funcMap.addOne((CheckDivZeroLabel, translateDivZeroLabel))
                        }

                        currentLabel.buf += CmpASM(Imm(0), ScratchRegs(1), DWord)
                        currentLabel.buf += JmpASM(CheckDivZeroLabel, Equal)
                        currentLabel.buf += DivASM(ScratchRegs.head, ScratchRegs.head, ScratchRegs(1)) // note that first 2 args are ignored for x86

                        (divMod: @unchecked) match {
                            case _: Div => currentLabel.buf += MovASM(DivRegister, ScratchRegs.head, DWord)
                            case _: Mod => currentLabel.buf += MovASM(ModRegister, ScratchRegs.head, DWord)
                        }
                        currentLabel.buf += MovsASM(ScratchRegs.head, ScratchRegs.head, DWord)
                    case other =>
                        if (!funcMap.contains(CheckOverflowLabel)) {
                            funcMap.addOne((CheckOverflowLabel, translateOverflowLabel))
                        }

                        (other: @unchecked) match {
                            case _: Add => currentLabel.buf += AddASM(ScratchRegs(1), ScratchRegs.head, ScratchRegs.head, DWord)
                            case _: Sub => currentLabel.buf += SubASM(ScratchRegs(1), ScratchRegs.head, ScratchRegs.head, DWord)
                            case _: Mul => currentLabel.buf += MulASM(ScratchRegs(1), ScratchRegs.head, ScratchRegs.head, DWord)
                            case _: BAnd => currentLabel.buf += AndASM(ScratchRegs(1), ScratchRegs.head, ScratchRegs.head, DWord)
                            case _: BOr => currentLabel.buf += OrASM(ScratchRegs(1), ScratchRegs.head, ScratchRegs.head, DWord)
                        }
                        currentLabel.buf += JmpASM(CheckOverflowLabel, Overflow)
                        currentLabel.buf += MovsASM(ScratchRegs.head, ScratchRegs.head, DWord)
                }

            case op@ComparativeOp(lhs, rhs) =>  // covers both equality and comparison operators
                translateExpression(lhs)
                currentLabel.buf += PushASM(ScratchRegs(0))
                translateExpression(rhs)
                currentLabel.buf += PopASM(ScratchRegs(1))
                currentLabel.buf += CmpASM(ScratchRegs(0), ScratchRegs(1))
                val flag = op match {
                    case _: Eql => Equal
                    case _: NotEql => NotEqual
                    case _: GrtEql => GreaterEqual
                    case _: Grt => Greater
                    case _: LessEql => LessEqual
                    case _: Less => IR.Less
                }
                currentLabel.buf += SetASM(ScratchRegs(0), flag)
                currentLabel.buf += MovsASM(ScratchRegs(0), ScratchRegs(0), Byte)

            case op@LogicalOp(lhs, rhs) =>
                translateExpression(lhs)

                val endLabel = JumpLabel(s".L_logical_op_$branchCounter")
                branchCounter += 1
                
                currentLabel.buf += CmpASM(TrueImm, ScratchRegs.head)
                val flag = op match {
                    case _: And => NotEqual
                    case _: Or => Equal
                }
                currentLabel.buf += JmpASM(endLabel, flag)
                translateExpression(rhs)
                currentLabel.buf += CmpASM(TrueImm, ScratchRegs.head)
                currentLabel.buf += endLabel
                currentLabel.buf += SetASM(ScratchRegs.head, Equal)
                currentLabel.buf += MovsASM(ScratchRegs.head, ScratchRegs.head, Byte)
                
            case _ => println("This should never happen")
        }

        currentLabel.buf += Comment(s"End expression")
    }

    /**
      * Generates code for arithmetic and logical unary operations
      */
    def translateUnOp(unop: UnOp)(implicit currentLabel: LabelInfo, st: SymbolTable): Unit = {
        unop match {
            case BNeg(expr) => 
                translateExpression(expr)
                currentLabel.buf += NotASM(ScratchRegs.head, ScratchRegs.head)
            case Len(expr) => 
                translateExpression(expr)
                currentLabel.buf += MovASM(ScratchRegs.head, ScratchRegs(1)) // can this be optimised out?
                currentLabel.buf += MovsASM(Memory(ScratchRegs(1), -sizeToInt(DWord)), ScratchRegs.head, DWord)

            case Not(expr) =>
                translateExpression(expr)
                currentLabel.buf += CmpASM(TrueImm, ScratchRegs.head) // idk if this one is necessary
                currentLabel.buf += SetASM(ScratchRegs.head, NotEqual, Byte)
                currentLabel.buf += MovsASM(ScratchRegs.head, ScratchRegs.head, Byte)
            case Ord(expr) => translateExpression(expr)
            case Chr(expr) =>
                translateExpression(expr)

                if (!funcMap.contains(CheckBadCharLabel)) {
                    funcMap.addOne((CheckBadCharLabel, translateBadCharLabel))
                }

                currentLabel.buf ++= ListBuffer(
                    TestASM(Imm(-128), ScratchRegs.head),
                    MovASM(ScratchRegs.head, ParamRegs(1), NotEqual, QWord),
                    JmpASM(CheckBadCharLabel, NotEqual)
                )
            case Neg(expr) =>
                translateExpression(expr)

                if (!funcMap.contains(CheckOverflowLabel)) {
                    funcMap.addOne((CheckOverflowLabel, translateOverflowLabel))
                }

                currentLabel.buf ++= ListBuffer(
                    MovsASM(ScratchRegs(0), ScratchRegs(0), DWord),
                    MovASM(ScratchRegs(0), ScratchRegs(1)), // next scratch reg
                    MovASM(Imm(0), ScratchRegs(0), DWord),
                    SubASM(ScratchRegs(1), ScratchRegs(0), ScratchRegs(0), DWord),
                    JmpASM(CheckOverflowLabel, Overflow),
                    MovsASM(ScratchRegs(0), ScratchRegs(0), DWord),
                )
        }
    }

    /**
      * Generates code for lvalues
      */
    def translateLValue(lvalue: LValue, writeTo: Boolean = false)(implicit currentLabel: LabelInfo, st: SymbolTable): Unit = {
        // Implement translation for LValues here
        lvalue match {
            case node: ArrayVal => translateArrayElem(node, writeTo)
            case node: PairElem => translatePairElem(node, writeTo)
            case node: Var => translateVar(node, writeTo)
        }
    }

    /**
      * Generates code for rvalues
      */
    def translateRValue(rvalue: RValue)(implicit currentLabel: LabelInfo, st: SymbolTable): Unit = {
        // Implement translation for Return statement here
        rvalue match {
            case node: ArrayLiteral => translateArrayLiteral(node)
            case node: PairElem => translatePairElem(node)
            case node: PairCons => translatePairCons(node)
            case node: ArrayCons => translateArrayCons(node)
            case node: FuncCall => translateCall(node)
            case node: Expr => translateExpression(node)
        }
    }

    def translateArrayLiteral(node: ArrayLiteral)(implicit currentLabel: LabelInfo, st: SymbolTable): Unit = {
        // Implement translation for Array Literals here
        if (!funcMap.contains(MallocWrapperLabel)) {
            funcMap.addOne((MallocWrapperLabel, translateMallocLabel))
        }

        val singleSize = semanticToSize(node.enclosingType)
        val totalSize = sizeToInt(DWord) + singleSize * node.exprs.size
        currentLabel.buf += MovASM(Imm(totalSize), ParamRegs.head, DWord)
        currentLabel.buf += CallASM(MallocWrapperLabel)
        currentLabel.buf += MovASM(ScratchRegs(0), ArrayPointer)
        currentLabel.buf += AddASM(Imm(sizeToInt(DWord)), ArrayPointer, ArrayPointer)
        currentLabel.buf += MovASM(Imm(node.exprs.size), ScratchRegs(0))
        currentLabel.buf += MovASM(ScratchRegs(0), Memory(ArrayPointer, -sizeToInt(DWord)))
        node.exprs.zipWithIndex.foreach { case (expr, index) =>
            translateExpression(expr)
            currentLabel.buf += MovASM(ScratchRegs(0), Memory(ArrayPointer, index * singleSize))
        }
        currentLabel.buf += MovASM(ArrayPointer, ScratchRegs(0))
    }

    def translateArrayCons(node: ArrayCons)(implicit currentLabel: LabelInfo, st: SymbolTable): Unit = {
        // Implement translation for Array Constructor here
        if (!funcMap.contains(MallocWrapperLabel)) {
            funcMap.addOne((MallocWrapperLabel, translateMallocLabel))
        }

        if (!funcMap.contains(CheckAllocLabel)) {
            funcMap.addOne((CheckAllocLabel, translateBadAllocLabel))
        }

        val singleSize = semanticToSize(node.declType)
        val offsetSize = sizeToInt(DWord)
        translateExpression(node.lengthExpr)

        // Check that malloc value is not less than 0
        currentLabel.buf += CmpASM(Imm(0), ScratchRegs.head, DWord)
        currentLabel.buf += MovASM(ScratchRegs.head, ParamRegs(1), IR.Less)
        currentLabel.buf += JmpASM(CheckAllocLabel, IR.Less)

        // Save the size of the expression for later
        currentLabel.buf += PushASM(ScratchRegs.head)

        // Calculate the size of what is to be malloced
        currentLabel.buf += MulASM(Imm(sizeToInt(singleSize)), ScratchRegs.head, ScratchRegs.head)
        currentLabel.buf += AddASM(Imm(offsetSize), ScratchRegs.head, ScratchRegs.head)

        // Call malloc 
        currentLabel.buf += MovASM(ScratchRegs.head, ParamRegs.head, DWord)
        currentLabel.buf += CallASM(MallocWrapperLabel)

        // Array pointer points to start of array (not including size offset)
        currentLabel.buf += MovASM(ScratchRegs(0), ArrayPointer)
        currentLabel.buf += AddASM(Imm(sizeToInt(DWord)), ArrayPointer, ArrayPointer)
        
        // Pop the size from stack and store into array - 4
        currentLabel.buf += PopASM(ScratchRegs(0))
        currentLabel.buf += MovASM(ScratchRegs(0), Memory(ArrayPointer, -sizeToInt(DWord)))

        // Return into Return reg
        currentLabel.buf += MovASM(ArrayPointer, ReturnReg)
        
    }

    def translatePairCons(node: PairCons)(implicit currentLabel: LabelInfo, st: SymbolTable): Unit = {
        // Implement translation for Free statement here
        if (!funcMap.contains(MallocWrapperLabel)) {
            funcMap.addOne((MallocWrapperLabel, translateMallocLabel))
        }

        currentLabel.buf += Comment(s"Begin Pair Cons $node")

        val PairSize = Imm(16)
        currentLabel.buf += MovASM(PairSize, ParamRegs.head, DWord)
        currentLabel.buf += CallASM(MallocWrapperLabel)
        currentLabel.buf += MovASM(ScratchRegs.head, ArrayPointer)
        translateExpression(node.fstExpr)
        currentLabel.buf += MovASM(ScratchRegs.head, Memory(ArrayPointer))
        translateExpression(node.sndExpr)
        currentLabel.buf += MovASM(ScratchRegs.head, Memory(ArrayPointer, sizeToInt(QWord)))
        currentLabel.buf += MovASM(ArrayPointer, ScratchRegs.head)

        currentLabel.buf += Comment("End Pair Cons")
        
    }
    
    /**
      * Generate code for 'call'
      * All parameters are put on the stack during function call function is called
      */
    def translateCall(node: Call)(implicit currentLabel: LabelInfo, st: SymbolTable): Unit = {
        // Implement translation for FuncCall statement here
        val paramScope = node.func.scope
        allocateStackVariables()(currentLabel, paramScope)
        node.args.zip(node.func.params).foreach { case (expr, param) =>
            translateExpression(expr)
            assignParam(param)(currentLabel, paramScope)
        }
        currentLabel.buf += CallASM(WaccFuncLabel(node.name))
        currentLabel.buf += MovASM(ReturnReg, ScratchRegs.head)
        popStackVariables()(currentLabel, paramScope)
    }

    /**
      * Gets the value of variable from ST and possibly overrwrites based on @param writeTo
      */
    def translateVar(node: Var, writeTo: Boolean = false)(implicit currentLabel: LabelInfo, st: SymbolTable): Unit = {
        assert(st.contains(node.name), s"Variable ${node.name} was not contained in the symbol table")
        assert(st.hasLocation(node.name), s"The location of variable ${node.name} was not contained in the symbol table")

        val location = st.getLocation(node.name)
        val declType = st.typeof(node.name)
        val size = semanticToSize(declType)

        if (writeTo) {
            currentLabel.buf += MovASM(ScratchRegs.head, location, size)
        } else {
            size match {
                case QWord => currentLabel.buf += MovASM(location, ScratchRegs.head, size)
                case size => currentLabel.buf += MovsASM(location, ScratchRegs.head, size)
            }
        }

    }

    def translateArrayElem(node: ArrayVal, writeTo: Boolean = false)(implicit currentLabel: LabelInfo, st: SymbolTable): Unit = {
        
        var location = st.getLocation(node.name)
        var innerSize = semanticToSize(node.enclosingType)

        if (writeTo) currentLabel.buf += PushASM(ScratchRegs.head)

        /**
          * Iterate through each of the expressions
          */
        node.exprs.zipWithIndex.foreach { case (expr, index) =>
            val store = writeTo && index == node.exprs.size - 1
            val size = if (index == node.exprs.size - 1) innerSize else QWord
            val label = if (store) ArrayStoreLabel(size) else ArrayLoadLabel(size)

            if (!funcMap.contains(label)) {
                funcMap.addOne((label, translateArrayElemLabel(size, store)))
            }

            translateExpression(expr)
            currentLabel.buf += MovASM(ScratchRegs.head, IndexPointer, DWord)


            if (index != 0) currentLabel.buf += PopASM(location)

            currentLabel.buf += MovASM(location, ArrayPointer)

            if (store) currentLabel.buf += PopASM(ScratchRegs.head)

            currentLabel.buf += CallASM(label)

            if (index != node.exprs.size - 1) {
                location = ScratchRegs.head
                currentLabel.buf += PushASM(location)
            }
            
        }

    }

    /**
      * Get the pointer to the address of an LValue so that it can be directly modifed.
      * For now it is only used for pairs
      */
    def getPairAddress(lvalue: LValue)(implicit currentLabel: LabelInfo, st: SymbolTable): Unit = {
        (lvalue: @unchecked) match {
            case node@ArrayVal(name, exprs) => 
                translateArrayElem(node)
                currentLabel.buf += MovASM(ArrayPointerPointer, ScratchRegs.head)
                // todo: either remove array pointer or make translateArrayElem take in a boolean flag for pointer

            case p@PairElem(lvalue) =>
                getPairAddress(lvalue)
                currentLabel.buf += MovASM(Memory(ScratchRegs.head), ScratchRegs.head)

                // doesn't hurt to put here, although currently only called by translatePairElem which adds this anyway by default
                if (!funcMap.contains(CheckNullLabel)) {
                    funcMap.addOne((CheckNullLabel, translateNullLabel))
                }

                currentLabel.buf += CmpASM(NullImm, ScratchRegs.head)
                currentLabel.buf += JmpASM(CheckNullLabel, Equal)
                
                p match {
                    case _: FstPair => 
                    case _: SndPair => currentLabel.buf += AddASM(Imm(sizeToInt(QWord)), ScratchRegs.head, ScratchRegs.head)
                }

            case Var(name) =>
                st.getLocation(name) match {
                    case mem: Memory => currentLabel.buf += LeaASM(mem, ScratchRegs.head)
                    case reg: Register => currentLabel.buf += MovASM(reg, ScratchRegs.head)
                }
        }

    }

    /**
      * Pair elems are translated by calling getPairAddress and dereferencing the location
      */
    def translatePairElem(node: PairElem, writeTo: Boolean = false)(implicit currentLabel: LabelInfo, st: SymbolTable): Unit = {
        if (!funcMap.contains(CheckNullLabel)) {
            funcMap.addOne((CheckNullLabel, translateNullLabel))
        }
        
        val memLocation = Memory(ScratchRegs(0))
        val (src, dst) = if (writeTo) (ScratchRegs(1), memLocation)
                         else         (memLocation, ScratchRegs(0))

        currentLabel.buf += Comment(s"Begin Pair Elem $node")

        if (writeTo) currentLabel.buf += PushASM(ScratchRegs.head)
        getPairAddress(node)
        if (writeTo) currentLabel.buf += PopASM(ScratchRegs(1))

        currentLabel.buf += MovASM(src, dst)

        currentLabel.buf += Comment(s"End Pair Elem")
    }

    def translateFunction(func: Func)(implicit currentLabel: LabelInfo = LabelInfo.empty): LabelInfo = {
        currentLabel.buf += PushASM(BasePointer)
        currentLabel.buf += MovASM(StackPointer, BasePointer)

        implicit val paramScope = func.scope
        val bodyScope = paramScope.getChild()

        // Translate the function parameters and assign locations into ST
        stackOffsets.push(0)
        func.params.foreach { param =>
            val size = sizeToInt(semanticToSize(syntaxToSemanticType(param.declType)))
            val funcMemLocation = Memory(BasePointer, 16 + stackOffsets.head)

            incrementStackOffset(size)
            paramScope.updateLocation(param.name, funcMemLocation)
        }
        stackOffsets.pop()

        translateBlock(func.stats)(currentLabel, bodyScope)

        currentLabel.buf += MovASM(BasePointer, StackPointer)
        currentLabel.buf += PopASM(BasePointer)
        currentLabel.buf += RetASM

        currentLabel
    }





    ///////////////// UTILITY FUNCTIONS ///////////////////

    /**
      * Adds a string constant, also makes sure to translate escape characters 
      */
    def addStringConstant(string: String)(implicit currentLabel: LabelInfo, st: SymbolTable): StringLabel = {
        val retVal = StringLabel(s".L.str$branchCounter", toRaw(string))
        branchCounter += 1;
        if (!currentLabel.readonlys.contains(retVal)) currentLabel.readonlys.add(retVal)
        retVal
    }

    /**
      * Generates 'free' label 
      */
    def translateFreeLabel(isArray: Boolean): LabelInfo = {
        var currentLabel: LabelInfo = LabelInfo.empty
        currentLabel.buf ++ ListBuffer(
            PushASM(BasePointer),
            MovASM(StackPointer, BasePointer),
            AndASM(AlignmentMaskImm, StackPointer, StackPointer)
        )
        
        // Based on flag, pair or array is free so null check is performed
        if (!isArray) {
            if (!funcMap.contains(CheckNullLabel)) {
                funcMap.addOne((CheckNullLabel, translateNullLabel))
            }

            currentLabel.buf += CmpASM(NullImm, ParamRegs.head)
            currentLabel.buf += JmpASM(CheckNullLabel, Equal)
        }
        
        currentLabel.buf ++= ListBuffer(
            CallASM(FreeLabel),
            MovASM(BasePointer, StackPointer),
            PopASM(BasePointer),
            RetASM
        )

        currentLabel
    }
    
    def translateArrayElemLabel(size: Size, store: Boolean): LabelInfo = {
        if (!funcMap.contains(CheckBoundsLabel)) {
            funcMap.addOne((CheckBoundsLabel, translateBoundsLabel))
        }

        val (src, dst) = if (store) (ScratchRegs.head, Memory(ArrayPointer, IndexPointer, size))
                         else (Memory(ArrayPointer, IndexPointer, size), ScratchRegs.head)
        
        
        val currentLabel: LabelInfo = LabelInfo(ListBuffer(
            Comment(s"Special calling convention: array ptr passed in ${targetConfig.opStr(ArrayPointer)}, index in ${targetConfig.opStr(IndexPointer)}, ${if (store) "value to store in" else "and return into"} ${targetConfig.opStr(ScratchRegs.head)}"),
            PushASM(ScratchRegs(1)),
            CmpASM(Imm(0), IndexPointer, DWord),
            MovASM(IndexPointer, ParamRegs(1), IR.Less),
            JmpASM(CheckBoundsLabel, IR.Less),
            MovASM(Memory(ArrayPointer, -sizeToInt(DWord)), ScratchRegs(1), DWord),
            CmpASM(ScratchRegs(1), IndexPointer, DWord),
            MovASM(IndexPointer, ParamRegs(1), GreaterEqual),
            JmpASM(CheckBoundsLabel, GreaterEqual),
        ))

        if (!store) currentLabel.buf += LeaASM(Memory(ArrayPointer, IndexPointer, size), ArrayPointerPointer)

        currentLabel.buf ++= ListBuffer(
            if (store || size == QWord) MovASM(src, dst, size)
            else MovsASM(src, dst, size),
            PopASM(ScratchRegs(1)),
            RetASM
        )

        currentLabel
    }

    /**
      * Generates 'malloc' label for allocating space for arrays and pairs
      */
    def translateMallocLabel: LabelInfo = {
        if (!funcMap.contains(CheckOOMLabel)) {
            funcMap.addOne((CheckOOMLabel, translateOOMLabel))
        }

        LabelInfo(ListBuffer(
            PushASM(BasePointer),
            MovASM(StackPointer, BasePointer),
            AndASM(AlignmentMaskImm, StackPointer, StackPointer),
            CallASM(MallocLabel),
            CmpASM(NullImm, ReturnReg),
            JmpASM(CheckOOMLabel, Equal),
            MovASM(BasePointer, StackPointer),
            PopASM(BasePointer),
            RetASM
        ))
    }

    def translateExitLabel: LabelInfo = {
        LabelInfo(ListBuffer(
            PushASM(BasePointer),
            MovASM(StackPointer, BasePointer),
            AndASM(AlignmentMaskImm, StackPointer, StackPointer),
            CallASM(ExitLabel),
            MovASM(BasePointer, StackPointer),
            PopASM(BasePointer),
            RetASM
        ))
    }

    /**
      * Generate 'read' label depending on 'size' parameter which depends on whether we are reading into a 'char' or 'int'
      */
    def translateReadLabel(label: WrapperFuncLabel, fstring: String, size: Size): LabelInfo = {
        val stringLabel = StringLabel(s".L.${label.name}_string", fstring)

        LabelInfo(
            ListBuffer(
                PushASM(BasePointer),
                MovASM(StackPointer, BasePointer),
                AndASM(AlignmentMaskImm, StackPointer, StackPointer),
                SubASM(ReadOffsetImm, StackPointer, StackPointer),
                MovASM(ParamRegs(0), Memory(StackPointer), size),
                LeaASM(Memory(StackPointer), ParamRegs(1)),
                LeaASM(Memory(InstructionPointer, stringLabel), ParamRegs(0)),
                MovASM(Imm(0), R0, Byte), // for SIMD purposes
                CallASM(ScanFormatted),
                MovsASM(Memory(StackPointer), ReturnReg, size),
                AddASM(ReadOffsetImm, StackPointer, StackPointer),
                MovASM(BasePointer, StackPointer),
                PopASM(BasePointer),
                RetASM
            ),
            HashSet(stringLabel)
        )
    }

    /**
      * Generates 'print' label depending on the type of what it is printing
      */
    def translatePrintLabel(label: WrapperFuncLabel, fstring: String, _type: SemType): LabelInfo = {
        val stringLabel = StringLabel(s".L.${label.name}_string", fstring)
        val size = semanticToSize(_type)
        val stringOffset = -4

        val currentLabel: LabelInfo = LabelInfo.empty

        currentLabel.readonlys.addOne(stringLabel)

        val buf: ListBuffer[Line] = ListBuffer(
            PushASM(BasePointer),
            MovASM(StackPointer, BasePointer),
            AndASM(AlignmentMaskImm, StackPointer, StackPointer)
        )

        _type match {
            case SemBool =>
                val trueLabel = JumpLabel(s".L${label.name}_true")
                val falseLabel = JumpLabel(s".L${label.name}_false")
                val trueStringLabel = StringLabel(s".L.${label.name}_string_true", "true")
                val falseStringLabel = StringLabel(s".L.${label.name}_string_false", "false")

                currentLabel.readonlys.addOne(trueStringLabel)
                currentLabel.readonlys.addOne(falseStringLabel)

                buf ++= ListBuffer(
                    CmpASM(FalseImm, ParamRegs.head),
                    JmpASM(falseLabel, NotEqual),
                    LeaASM(Memory(InstructionPointer, falseStringLabel), ParamRegs(2)),
                    JmpASM(trueLabel),
                    falseLabel,
                    LeaASM(Memory(InstructionPointer, trueStringLabel), ParamRegs(2)),
                    trueLabel,
                    MovASM(Memory(ParamRegs(2), stringOffset), ParamRegs(1), DWord)
                )
            case SemString | SemArray(SemChar) =>
                buf += MovASM(ParamRegs(0), ParamRegs(2))
                buf += MovASM(Memory(ParamRegs(0), stringOffset), ParamRegs(1), DWord)
            case other =>
                buf += MovASM(ParamRegs(0), ParamRegs(1), size)
        }

        buf ++= ListBuffer(
            LeaASM(Memory(InstructionPointer, stringLabel), ParamRegs.head),
            MovASM(Imm(0), R0, Byte), // for SIMD purposes
            CallASM(PrintFormatted),
            MovASM(Imm(0), ParamRegs.head),
            CallASM(FileFlush),
            MovASM(BasePointer, StackPointer),
            PopASM(BasePointer),
            RetASM
        )

        currentLabel.buf ++= buf

        currentLabel
    }

    /**
      * Label for 'println' which just prints a new line, usually called in conjunction with 'print'
      */
    def translatePrintlnLabel: LabelInfo = {
        val stringLabel = StringLabel(s".L.println_string", "")

        LabelInfo(ListBuffer(
            PushASM(BasePointer),
            MovASM(StackPointer, BasePointer),
            AndASM(AlignmentMaskImm, StackPointer, StackPointer),
            LeaASM(Memory(InstructionPointer, stringLabel), ParamRegs.head),
            CallASM(Puts),
            MovASM(Imm(0), ParamRegs.head),
            CallASM(FileFlush),
            MovASM(BasePointer, StackPointer),
            PopASM(BasePointer),
            RetASM
        ), HashSet(stringLabel))
    }

    ////////////////// ERRORS ////////////////////

    /**
      * This section generates labels for each error type and then uses the 
      * corresponding error generator helper function
      */

    def translateNullLabel: LabelInfo = {
        translateErrorString(StringLabel(s".L._errNull_string", "fatal error: null pair dereferenced or freed\\n"))
    }

    def translateBadAllocLabel: LabelInfo = {
        translateErrorInt(StringLabel(s".L._errBadAlloc_string", "fatal error: negative value %d passed in as array length\\n"))
    }

    def translateBadCharLabel: LabelInfo = {
        translateErrorInt(StringLabel(s".L._errBadChar_string", "fatal error: int %d is not ascii character 0-127\\n"))
    }

    def translateBoundsLabel: LabelInfo = {
        translateErrorInt(StringLabel(s".L._errOutOfBounds_string", "fatal error: array index %d out of bounds\\n"))
    }
    
    def translateOOMLabel: LabelInfo = {
        translateErrorString(StringLabel(s".L._errOutOfMemory_string", "fatal error: out of memory\\n"))
    }

    def translateOverflowLabel: LabelInfo = {
        translateErrorString(StringLabel(s".L._errOverflow_string", "fatal error: integer overflow or underflow occurred\\n"))
    }

    def translateDivZeroLabel: LabelInfo = {
        translateErrorString(StringLabel(s".L._errDivZero_string", "fatal error: division or modulo by zero\\n"))
    }

    ////////// ERROR HELPERS ///////////

    def translateErrorString(errorLabel: StringLabel): LabelInfo = {
        if (!funcMap.contains(PrintStrLabel)) {
            funcMap.addOne((PrintStrLabel, translatePrintLabel(PrintStrLabel, "%.*s", SemString)))
        }

        LabelInfo(ListBuffer(
            AndASM(AlignmentMaskImm, StackPointer, StackPointer),
            LeaASM(Memory(InstructionPointer, errorLabel), ParamRegs.head),
            CallASM(PrintStrLabel),
            MovASM(ExitFailureImm, ParamRegs.head, Byte),
            CallASM(ExitLabel)
        ), HashSet(errorLabel))
    }

    def translateErrorInt(errorLabel: StringLabel): LabelInfo = {

        LabelInfo(ListBuffer(
            AndASM(AlignmentMaskImm, StackPointer, StackPointer),
            LeaASM(Memory(InstructionPointer, errorLabel), ParamRegs.head),
            MovASM(Imm(0), R0, Byte),
            CallASM(PrintFormatted),
            MovASM(Imm(0), ParamRegs.head),
            CallASM(FileFlush),
            MovASM(ExitFailureImm, ParamRegs.head, Byte),
            CallASM(ExitLabel)
        ), HashSet(errorLabel))
    }
}