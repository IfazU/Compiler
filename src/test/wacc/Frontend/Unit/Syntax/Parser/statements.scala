package wacc.unit.syntax.parser

import wacc._
import astFactory._
import org.scalactic.Bool
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers._
import parsley.Failure
import parsley.Parsley
import parsley.Result
import parsley.Success
import parsley.token.Lexer
import parsley.token.descriptions._
import parsley.token.predicate

class parserStatementTest extends AnyFlatSpec {
    val statParser = lexer.fully(parser.stmt)
    val funcParser = lexer.fully(parser.func)

    "\'skip\'" should "match stmt" in {
        statParser.parse("skip") shouldBe Success(Skip)
    }
    "\'free\'" should "match stmt" in {
        statParser.parse("free array") shouldBe Success(Free(Var("array")))
    }
    "New assignments" should "match stmt" in {
        statParser.parse("int i=0") shouldBe Success(AssignNew(IntType, "i", IntVal(0)))
    }
    "assignments" should "match stmt" in {
        statParser.parse("arr[1]=true") shouldBe Success(Assign(ArrayVal("arr", List(IntVal(1))), BoolVal(true)))
    }
    "\'read\' command" should "match stmt" in {
        statParser.parse("read x") shouldBe Success(Read(Var("x")))
    }
    "\'return\' with expr" should "match stmt" in {
        statParser.parse("return(4+3)") shouldBe Success(Return(Add(IntVal(4), IntVal(3))))
    }
    "\'exit\' command" should "match stmt" in {
        statParser.parse("exit(2000)") shouldBe Success(Exit(IntVal(2000)))
    }
    "\'print\' statements" should "match stmt" in {
        statParser.parse("print(\"Hello World\")") shouldBe Success(Print(StrVal("Hello World")))
    }
    "\'println\' statements" should "match stmt" in {
        statParser.parse("println(\"Hello Universe\")") shouldBe Success(Println(StrVal("Hello Universe")))
    }
    "\'if then else fi statements" should "match stmt" in {
        statParser.parse("if(x==4)then return(5) else return(4) fi") shouldBe
            Success(If(Eql(Var("x"), IntVal(4)), Return(IntVal(5)) :: Nil, Return(IntVal(4)) :: Nil))
    }

    "\'while do done\' statements" should "match stmt" in {
        statParser.parse("while(x==4) do arr[1]=true done") shouldBe
            Success(While(Eql(Var("x"), IntVal(4)), Assign(ArrayVal("arr", IntVal(1) :: Nil), BoolVal(true)) :: Nil))
    }

    "\'begin end\' statements" should "match stmt" in {
        statParser.parse("begin int i=0 end") shouldBe Success(Scope(AssignNew(IntType, "i", IntVal(0)) :: Nil))
    }

    "\'stmt ; stmt\' statements" should "match a list of statement" in {
        parser.stmtList.parse("int i=0; i=1") shouldBe Success(
          List(AssignNew(IntType, "i", IntVal(0)), Assign(Var("i"), IntVal(1)))
        )
    }
}
