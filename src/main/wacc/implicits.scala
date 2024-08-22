package wacc

import scala.language.implicitConversions

import ast._
import semAst._
import IR._

object implicits {
    /**
     * Implicit conversions of syntactic types to semantic
     * There is also one for convert semantic to their string representation (which are nice for tests)
     */
    implicit def syntaxToSemanticType(t: TypeRef): SemType = t match {
        case _: IntType => SemInt
        case _: CharType => SemChar
        case _: StringType => SemString
        case _: BoolType => SemBool
        case _: ErasedPair => SemErasedPair
        case ArrayType(t) => SemArray(syntaxToSemanticType(t))
        case PairType(t1, t2) => SemPair(syntaxToSemanticType(t1), syntaxToSemanticType(t2))
        case _: VoidType => SemVoid
        case _ => SemNone
    }

    implicit def semanticToSize(t: SemType): Size = t match {
        case SemInt => DWord
        case SemChar => Byte
        case SemBool => Byte
        case _ => QWord
    }

    implicit def sizeToInt(s: Size): Int = s match {
        case QWord => 8
        case Byte => 1
        case Word => 2
        case DWord => 4
    }

    implicit def semanticToString(t: SemType): String = t.toString()
}