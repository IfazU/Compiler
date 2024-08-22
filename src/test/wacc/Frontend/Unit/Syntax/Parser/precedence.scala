package wacc.unit.syntax.parser

import wacc._
import astFactory._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers._
import parsley.Failure
import parsley.Parsley
import parsley.Result
import parsley.Success
import parsley.token.Lexer
import parsley.token.predicate

class parserPrecedenceTests extends AnyFlatSpec {
    val exprParser = lexer.fully(parser.expr)

    "\'!\'' prefix" should "have tightest precedence" in {
        exprParser.parse("!x||y") shouldBe Success(Or(Not(Var("x")), Var("y")))
        exprParser.parse("!true&&false") shouldBe Success(And(Not(BoolVal(true)), BoolVal(false)))
        exprParser.parse("true==!false") shouldBe Success(Eql(BoolVal(true), Not(BoolVal(false))))
        exprParser.parse("true!=!false") shouldBe Success(NotEql(BoolVal(true), Not(BoolVal(false))))
    }

    "\'-\' prefix" should "have tightest precedence" in {
        /* this is not a good test since this does intliteral with - sign, not actually minus prefix for expr
        exprParser.parse("-4+3") shouldBe Success(Add(IntVal(-4), IntVal(3)))
        exprParser.parse("-4+3") shouldBe Success(Add(Neg(IntVal(4)), IntVal(3)))
         */
    }
    "\'len\' prefix" should "have tightest precedence" in {
        exprParser.parse("len(x)+y") shouldBe Success(Add(Len(Var("x")), Var("y")))
    }
    "\'ord\' prefix" should "have tightest precedence" in {
        exprParser.parse("ord('c')+123") shouldBe Success(Add(Ord(CharVal('c')), IntVal(123)))
    }
    "\'chr\' prefix" should "have tightest precedence" in {
        exprParser.parse("chr(65)!=\'A\'") shouldBe Success(NotEql(Chr(IntVal(65)), CharVal('A')))
    }

    "\'*\' operator" should "have 2nd tightest precedence" in {
        exprParser.parse("4*3+5") shouldBe Success(Add(Mul(IntVal(4), IntVal(3)), IntVal(5)))
        exprParser.parse("4-3*5") shouldBe Success(Sub(IntVal(4), Mul(IntVal(3), IntVal(5))))
    }

    "\'%\' operator" should "have 2nd tightest precedence" in {
        exprParser.parse("1%2*3") shouldBe Success(Mul(Mod(IntVal(1), IntVal(2)), IntVal(3)))
        exprParser.parse("a%b+c") shouldBe Success(Add(Mod(Var("a"), Var("b")), Var("c")))
    }

    "\'/\' operator" should "have 2nd tightest precedence" in {
        exprParser.parse("4/3+5") shouldBe Success(Add(Div(IntVal(4), IntVal(3)), IntVal(5)))
        exprParser.parse("4-c/5") shouldBe Success(Sub(IntVal(4), Div(Var("c"), IntVal(5))))
    }

    "\'+\' operator" should "have 3rd tightest precedence" in {
        exprParser.parse("4+x>2") shouldBe Success(Grt(Add(IntVal(4), Var("x")), IntVal(2)))
        exprParser.parse("x>y+1") shouldBe Success(Grt(Var("x"), Add(Var("y"), IntVal(1))))
    }
    "\'-\' operator" should "have 3rd tightest precedence" in {
        exprParser.parse("x<=y-1") shouldBe Success(LessEql(Var("x"), Sub(Var("y"), IntVal(1))))
        exprParser.parse("4-x<y") shouldBe Success(Less(Sub(IntVal(4), Var("x")), Var("y")))
    }

    "\'>\', \'<\', \'>=\', \'<=\' symbols" should "have 4th tightest precedence" in {
        exprParser.parse("x>5==true") shouldBe Success(Eql(Grt(Var("x"), IntVal(5)), BoolVal(true)))
        exprParser.parse("3<3==false") shouldBe Success(Eql(Less(IntVal(3), IntVal(3)), BoolVal(false)))
        exprParser.parse("x>=1==y<=2") shouldBe Success(Eql(GrtEql(Var("x"), IntVal(1)), LessEql(Var("y"), IntVal(2))))
    }

    "\'==\' symbol" should "have 5th tightest precedence" in {
        exprParser.parse("x==5&&true") shouldBe Success(And(Eql(Var("x"), IntVal(5)), BoolVal(true)))
        exprParser.parse("y==2||x==true") shouldBe Success(Or(Eql(Var("y"), IntVal(2)), Eql(Var("x"), BoolVal(true))))
    }

    "\'&&\' symbol" should "have 6th tightest precedence" in {
        exprParser.parse("x&&y||z") shouldBe Success(Or(And(Var("x"), Var("y")), Var("z")))
        exprParser.parse("true&&false||x&&y") shouldBe Success(
          Or(And(BoolVal(true), BoolVal(false)), And(Var("x"), Var("y")))
        )
    }

}
