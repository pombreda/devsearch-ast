package devsearch.parsers

import devsearch.ast._
import devsearch.ast.Modifiers._
import devsearch.ast.Empty._

import scala.util.parsing.combinator._

object JsParser extends Parser {

  def language = "JavaScript"

  def parse(source: Source) = new SourceParser(source).parse

  class SourceParser(source: Source) extends RegexParsers {

    val comment = """//[^\n]*\n|//[^\n]*$|/\*(.|[\r\n])*?\*/""".r

    var parsingRegex: Boolean = false
    var partialComment: Option[String] = None
    override protected def handleWhiteSpace(source: java.lang.CharSequence, offset: Int): Int = {

      def parseComments(offset: Int): Int = {
        val spaceOffset = whiteSpace findPrefixMatchOf (source.subSequence(offset, source.length)) match {
          case Some(matched) => offset + matched.end
          case None => offset
        }

        val commentOffset = comment findPrefixMatchOf (source.subSequence(spaceOffset, source.length)) match {
          case Some(matched) =>
            partialComment = partialComment.map(_ + "\n" + matched.toString) orElse Some(matched.toString)
            spaceOffset + matched.end
          case None => spaceOffset
        }

        if (commentOffset == offset) offset else parseComments(commentOffset)
      }

      if (!parsingRegex) parseComments(offset) else offset
    }

    def consumeComment[T <: Commentable](ast: T): ast.type = {
      partialComment.foreach(ast.appendComment(_))
      partialComment = None
      ast
    }

    def convertPosition(pos: scala.util.parsing.input.Position): devsearch.ast.Position =
      new SimplePosition(source, pos.line, pos.column)

    def withPos[T <% devsearch.ast.Positional with Commentable](p: => Parser[T]): Parser[T] = Parser { in =>
      val offset = in.offset
      val start = handleWhiteSpace(in.source, offset)
      p(in.drop (start - offset)) match {
        case Success(t, in1) =>
          Success(consumeComment(t.setPos(convertPosition(in.pos))).asInstanceOf[T], in1)
        case ns: NoSuccess => ns
      }
    }

    implicit class PositionedFun[T,U <: devsearch.ast.Positional](f: T => U) extends (T => U) with devsearch.ast.Positional with Commentable {
      def apply(arg: T) = f(arg).setPos(this.pos)
    }

    lazy val Keyword = "break"      |
                       "do"         |
                       "instanceof" |
                       "typeof"     |
                       "case"       |
                       "else"       |
                       "new"        |
                       "var"        |
                       "catch"      |
                       "finally"    |
                       "return"     |
                       "void"       |
                       "continue"   |
                       "for"        |
                       "switch"     |
                       "while"      |
                       "debugger"   |
                       "function"   |
                       "this"       |
                       "with"       |
                       "default"    |
                       "if"         |
                       "throw"      |
                       "delete"     |
                       "in"         |
                       "try"

    lazy val BitwiseANDExpressionNoIn = withPos(EqualityExpressionNoIn * (BitwiseANDOperator ^^ makeBinaryOp))

    lazy val Elision = rep1(",") ^^ { _ map (a => devsearch.ast.NullLiteral) }

    lazy val Stmt = withPos(
      StmtsBlock          |
      VariableStatement   |
      EmptyStatement      |
      LabelledStatement   |
      IfStatement         |
      IterationStatement  |
      ContinueStatement   |
      BreakStatement      |
      ImportStatement     |
      ReturnStatement     |
      WithStatement       |
      SwitchStatement     |
      ThrowStatement      |
      TryStatement        |
      ExpressionStatement )

    lazy val VariableDeclarationNoIn = withPos(Identifier ~ opt(InitialiserNoIn) ^^ { case id ~ i => ValDef(NoModifiers, id, Nil, NoType, i getOrElse Empty[Expr]) })

    lazy val LogicalANDExpression = withPos(BitwiseORExpression * (LogicalANDOperator ^^ makeBinaryOp))

    lazy val ArgumentList = rep1sep(AssignmentExpression, ",")

    lazy val LogicalOROperator = "||"

    lazy val PostfixOperator = "++" | "--"

    lazy val ExpressionStatement = Expression <~ opt(";")

    lazy val CaseClauses = rep1(CaseClause)

    lazy val Stmts: Parser[List[Statement]] = rep1(Stmt) ^^ { stmts =>
      stmts.flatMap(x => x match {
        case Block(inner) => inner
        case _ => List(x)
      })
    }

    lazy val BitwiseORExpressionNoIn = withPos(BitwiseXORExpressionNoIn * (BitwiseOROperator ^^ makeBinaryOp))

    lazy val CaseBlock = ("{" ~> opt(CaseClauses)) ~ DefaultClause ~ (opt(CaseClauses) <~ "}") ^^ { case c1 ~ d ~ c2 => c1.getOrElse(Nil) ++ List(d) ++ c2.getOrElse(Nil) } |
      "{" ~> opt(CaseClauses) <~ "}" ^^ { _.getOrElse(Nil) }

    lazy val AssignmentOperator = "="    |
                                  "*="   |
                                  "/="   |
                                  "%="   |
                                  "+="   |
                                  "-="   |
                                  "<<="  |
                                  ">>="  |
                                  ">>>=" |
                                  "&="   |
                                  "^="   |
                                  "|="  

    lazy val FunctionExpression = withPos("function" ~> opt(Identifier) ~
      withPos(("(" ~> opt(FormalParameterList) <~ ")") ~ StmtsBlock ^^ { case params ~ body =>
        val args = params.toList.flatten.map(name => ValDef(NoModifiers, name, Nil, NoType, NoExpr))
        FunctionLiteral(args, NoType, body)
      }) ^^ {
        case Some(name) ~ fun => Block(List(ValDef(NoModifiers, name, Nil, NoType, fun), Ident(name)))
        case None ~ fun => fun
      })

    lazy val Finally = withPos("finally" ~> StmtsBlock)

    lazy val SourceElement = withPos(FunctionDeclaration | Stmt)

    lazy val CaseClause = ("case" ~> Expression) ~ withPos(":" ~> opt(Stmts) ^^ { ss => Block(ss.toList.flatten) }) ^^ { case e ~ ss => e -> ss }

    lazy val EmptyStatement = ";" ^^^ { NoStmt }

    lazy val ReturnStatement = withPos("return" ~> opt(Expression) <~ opt(";") ^^ { e => Return(e getOrElse NoExpr) })

    lazy val PostfixExpression = withPos(LeftHandSideExpression ~ PostfixOperator ^^ { case e ~ op => UnaryOp(e, op, true) } | LeftHandSideExpression)

    lazy val AdditiveOperator = "+" | "-"

    lazy val MemberExpressionPart: Parser[Expr => Expr] = "[" ~> Expression <~ "]" ^^ { x => (y: Expr) => ArrayAccess(y, x) } |
                                                          "." ~> Identifier ^^ { x => (y: Expr) => FieldAccess(y, x, Nil) }  

    lazy val BitwiseANDExpression = withPos(EqualityExpression * (BitwiseANDOperator ^^ makeBinaryOp))

    lazy val EqualityExpression = withPos(RelationalExpression * (EqualityOperator ^^ makeBinaryOp))

    lazy val VariableDeclarationList = rep1sep(VariableDeclaration, ",")

    lazy val MultiplicativeExpression = withPos(UnaryExpression * (MultiplicativeOperator ^^ makeBinaryOp))

    lazy val ConditionalExpressionNoIn = withPos(
      (LogicalORExpressionNoIn <~ "?") ~ (AssignmentExpression <~ ":") ~ AssignmentExpressionNoIn ^^ { case c ~ i ~ e => TernaryOp(c, i, e) } |
      LogicalORExpressionNoIn)

    lazy val BreakStatement = withPos("break" ~> opt(Identifier) <~ opt(";") ^^ { Break(_) })

    lazy val VariableDeclarationListNoIn = rep1sep(VariableDeclarationNoIn, ",")

    def combineMember(soFar: Expr, constructor: Expr => Expr) = constructor(soFar)

    lazy val MemberExpressionForIn = withPos(
      FunctionExpression |
      PrimaryExpression ~ rep(MemberExpressionPart) ^^ { case start ~ mods => mods.foldLeft(start)(combineMember) })

    lazy val AssignmentExpression: Parser[Expr] = withPos(
      LeftHandSideExpression ~ AssignmentOperator ~ AssignmentExpression ^^ { case lhs ~ op ~ rhs => Assign(lhs, rhs, if (op == "=") None else Some(op.init)) } |
      ConditionalExpression)

    lazy val SourceElements: Parser[List[Statement]] = rep1(SourceElement) ^^ { stmts =>
      stmts.flatMap(x => x match {
        case Block(ss) => ss
        case _ => List(x)
      })
    }

    lazy val EqualityOperator = "==" | "!=" | "===" | "!=="

    lazy val MultiplicativeOperator = "*" | "/" | "%"

    lazy val LogicalORExpressionNoIn = withPos(LogicalANDExpressionNoIn * (LogicalOROperator ^^ makeBinaryOp))

    lazy val ImportStatement = withPos("import" ~> Name ~ (opt("." ~> "*") <~ ";") ^^ { case names ~ wild => Import(names.mkString("."), wild.isDefined, false) })

    lazy val Identifier = not(Keyword ~ "\\b") ~> """([A-Za-z\$_\xA0-\uFFFF]|\\(x|u)[0-9a-fA-F]{2,4})([A-Za-z0-9\$_\xA0-\uFFFF]|\\(x|u)[0-9a-fA-F]{2,4})*""".r

    lazy val StmtsBlock: Parser[Block] = withPos("{" ~> opt(Stmts) <~ "}" ^^ { stmts => Block(stmts.toList.flatten) });

    lazy val MemberExpression = withPos(
      (FunctionExpression | PrimaryExpression) ~ rep(MemberExpressionPart) ^^ { case start ~ mods => mods.foldLeft(start)(combineMember) } |
      AllocationExpression)

    lazy val ThrowStatement = withPos("throw" ~> Expression <~ opt(";") ^^ { Throw(_) })

    lazy val RelationalExpression = withPos(ShiftExpression * (RelationalOperator ^^ makeBinaryOp))

    lazy val InitialiserNoIn = withPos("=" ~> AssignmentExpressionNoIn)

    lazy val VariableStatement = withPos("var" ~> VariableDeclarationList <~ opt(";") ^^ { stmtBlock(_) })

    lazy val BitwiseXOROperator = "^"

    lazy val CallExpressionForIn = withPos(MemberExpressionForIn ~ Arguments ~ rep(CallExpressionPart) ^^ {
      case exp ~ args ~ parts => parts.foldLeft[Expr](args.map(a => FunctionCall(exp, Nil, a)) getOrElse exp)(combineMember)
    })

    lazy val CallExpression = withPos(MemberExpression ~ Arguments ~ rep(CallExpressionPart) ^^ {
      case exp ~ args ~ parts => parts.foldLeft[Expr](args.map(a => FunctionCall(exp, Nil, a)) getOrElse exp)(combineMember)
    })

    lazy val Literal: Parser[Expr] = withPos(
      RegularExpressionLiteral |
      DecimalLiteral           |
      HexIntegerLiteral        |
      StringLiteral            |
      BooleanLiteral           |
      NullLiteral)

    lazy val HexIntegerLiteral = withPos("""0[xX][0-9A-Fa-f]+""".r ^^ { SimpleLiteral(PrimitiveTypes.Special("Hex"), _) })
    
    lazy val BooleanLiteral = withPos(("true" | "false") ^^ { SimpleLiteral(PrimitiveTypes.Boolean, _) })

    lazy val NullLiteral = "null" ^^^ { devsearch.ast.NullLiteral }

    lazy val Program = withPos(opt(SourceElements) ^^ { elems => stmtBlock(elems.toList.flatten) })

    lazy val VariableDeclaration = withPos(Identifier ~ opt(Initialiser) ^^ { case id ~ i => ValDef(NoModifiers, id, Nil, NoType, i getOrElse Empty[Expr]) })

    lazy val ContinueStatement = withPos("continue" ~> opt(Identifier) <~ opt(";") ^^ { Continue(_) })

    lazy val SwitchStatement = withPos(("switch" ~> "(" ~> Expression <~ ")") ~ CaseBlock ^^ { case e ~ cb => Switch(e, cb) })

    lazy val BitwiseXORExpressionNoIn = withPos(BitwiseANDExpressionNoIn * (BitwiseXOROperator ^^ makeBinaryOp))

    lazy val RelationalExpressionNoIn = withPos(ShiftExpression * (RelationalNoInOperator ^^ makeBinaryOp))

    lazy val LogicalANDOperator = "&&"

    lazy val PropertyName = ((StringLiteral | DecimalLiteral) ^^ { _.value }) | Identifier

    lazy val StringLiteral = withPos(
      "\"([^\\\\\"]+|\\\\([bfnrtv'\"\\\\]|[0-3]?[0-7]{1,2}|x[0-9a-fA-F]{2}|u[0-9a-fA-F]{4}))*\"|'([^\\\\']+|\\\\([bfnrtv'\"\\\\]|[0-3]?[0-7]{1,2}|x[0-9a-fA-F]{2}|u[0-9a-fA-F]{4}))*'".r ^^ {
        SimpleLiteral(PrimitiveTypes.String, _)
      })

    lazy val RegularExpressionLiteral = withPos(
      (("/" ^^ { s => parsingRegex = true; s }) ~> RegularExpressionBody) ~ ("/" ~> opt(RegularExpressionFlags)) ^^ { case body ~ flags =>
        parsingRegex = false
        val args = List(body) ++ flags.toList
        FunctionCall(Ident(Names.REGEXP), Nil, args)
      })

    lazy val RegularExpressionBody = withPos(
      ("[^\\n*\\\\/\\[]".r | "\\\\[^\\n\\\\*/\\[]".r | ("[" ~> "([^\\n\\\\*/\\[\\]]|\\\\[^\\n\\\\*/\\[])+".r <~ "]")) ~
      ("[^\\n\\\\/\\[]".r | "\\\\[^\\n\\\\*/\\[]".r | ("[" ~> "([^\\n\\\\*/\\[\\]]|\\\\[^\\n\\\\*/\\[])+".r <~ "]")).* ^^ {
        case a ~ ss => SimpleLiteral(PrimitiveTypes.String, a + ss.mkString)
      })

    lazy val RegularExpressionFlags = withPos(Identifier ^^ { SimpleLiteral(PrimitiveTypes.String, _) })

    lazy val DecimalIntegerLiteral = "0" | """[1-9][0-9]*""".r 

    lazy val DecimalLiteral = withPos(
      DecimalIntegerLiteral ~ "." ~ """[0-9]*""".r ~ """([Ee][+-]?[0-9]+)?""".r ^^ { case a~b~c~d => SimpleLiteral(PrimitiveTypes.Double, a+b+c+d) } |
      "." ~ """[0-9]+""".r ~ """([Ee][+-]?[0-9]+)?""".r                         ^^ { case a~b~c   => SimpleLiteral(PrimitiveTypes.Double, a+b+c)   } |
      DecimalIntegerLiteral ~ """([Ee][+-]?[0-9]+)?""".r                        ^^ { case a~b     => SimpleLiteral(PrimitiveTypes.Double, a+b)     })
    
    lazy val ArgumentMemberExpressionParts = Arguments ~ rep(MemberExpressionPart) ^^ {
      case args ~ parts => (e: Expr) => parts.foldLeft[Expr](FunctionCall(e, Nil, args.toList.flatten))(combineMember)
    }

    def extractType(expr: Expr): ClassType = expr match {
      case FieldAccess(receiver, name, _) => ClassType(receiver, name, Nil, Nil).fromAST(expr)
      case ArrayAccess(receiver, SimpleLiteral(PrimitiveTypes.String, value)) => ClassType(receiver, value, Nil, Nil).fromAST(expr)
      case ArrayAccess(receiver, index) => ClassType(receiver, index.toString, Nil, Nil).fromAST(expr)
      case _ => ClassType(NoExpr, expr.toString, Nil, Nil).fromAST(expr)
    }

    lazy val AllocationExpression: Parser[Expr] = withPos(("new" ~> MemberExpression) ~ rep(ArgumentMemberExpressionParts) ^^ {
      case start ~ Nil =>
        ConstructorCall(extractType(start), Nil, Nil)
      case start ~ (args :: parts) =>
        parts.foldLeft[Expr](args(ConstructorCall(extractType(start), Nil, Nil)) match {
          case fc @ FunctionCall(ConstructorCall(tpe, Nil, Nil), Nil, args) => ConstructorCall(tpe, args, Nil).fromAST(fc)
          case cc => cc
        })(combineMember)
    })

    lazy val Catch = withPos(("catch" ~> "(" ~> Identifier) ^^ { Ident(_) }) ~ (")" ~> StmtsBlock) ^^ { case id ~ block => id -> block }

    lazy val TryStatement = withPos(
      "try" ~> StmtsBlock ~ Finally              ^^ { case b ~ f     => Try(b, Nil, f) }
    | "try" ~> StmtsBlock ~ Catch ~ opt(Finally) ^^ { case b ~ c ~ f => Try(b, List(c), f getOrElse NoStmt) })

    lazy val FormalParameterList = rep1sep(Identifier, ",")

    lazy val BitwiseORExpression = withPos(BitwiseXORExpression * (BitwiseOROperator ^^ makeBinaryOp))

    lazy val Expression: Parser[Expr] = withPos(rep1sep(AssignmentExpression, ",") ^^ { exprBlock(_) })

    lazy val AdditiveExpression = withPos(MultiplicativeExpression * (AdditiveOperator ^^ makeBinaryOp))

    lazy val ConditionalExpression = withPos(
      (LogicalORExpression ~ ("?" ~> AssignmentExpression) ~ (":" ~> AssignmentExpression)) ^^ { case c ~ i ~ e => TernaryOp(c, i, e) }
    | LogicalORExpression)

    lazy val UnaryExpression: Parser[Expr] = withPos(
      PostfixExpression
    | rep1(UnaryOperator) ~ PostfixExpression ^^ { case ops ~ e => ops.foldRight(e)((op:String, soFar:Expr) => UnaryOp(soFar, op, false)) })

    lazy val LeftHandSideExpression: Parser[Expr] = withPos(CallExpression | MemberExpression)

    lazy val FunctionDeclaration = withPos("function" ~> Identifier ~ ("(" ~> opt(FormalParameterList) <~ ")") ~ StmtsBlock ^^ {
      case name ~ params ~ body =>
        val args = params.toList.flatten.map(name => ValDef(NoModifiers, name, Nil, NoType, NoExpr))
        FunctionDef(NoModifiers, name, Nil, Nil, args, NoType, body)
    })

    lazy val Initialiser = withPos("=" ~> AssignmentExpression)

    lazy val CallExpressionPart = withPos(
      Arguments                ^^ { x => (y: Expr) => x.map(args => FunctionCall(y, Nil, args)) getOrElse y }
    | "[" ~> Expression <~ "]" ^^ { x => (y: Expr) => ArrayAccess(y, x) }
    | "." ~> Identifier        ^^ { x => (y: Expr) => FieldAccess(y, x, Nil) })

    lazy val RelationalNoInOperator = "<" | ">" | "<=" | ">=" | "instanceof"  

    lazy val AssignmentExpressionNoIn: Parser[Expr] = withPos(
      LeftHandSideExpression ~ AssignmentOperator ~ AssignmentExpressionNoIn ^^ { case lhs ~ op ~ rhs => Assign(lhs, rhs, if (op == "") None else Some(op.init)) }
    | ConditionalExpressionNoIn)

    lazy val PrimaryExpression = withPos(
      "this" ^^^ { This(NoExpr) }
    | ObjectLiteral
    | "(" ~> Expression <~ ")"
    | Identifier ^^ { Ident(_) }
    | ArrayLiteral
    | Literal)

    lazy val LogicalANDExpressionNoIn = withPos(BitwiseORExpressionNoIn * (LogicalANDOperator ^^ makeBinaryOp))

    lazy val PropertyNameAndValueList = rep1sep(PropertyNameAndValue, ",") <~ opt(",") | "," ^^^ { Nil }

    lazy val Arguments = "(" ~> opt(ArgumentList) <~ ")"

    lazy val ObjectLiteral = withPos("{" ~> opt(PropertyNameAndValueList) <~ "}" ^^ { elems => ConstructorCall(NoType, Nil, elems.toList.flatten) })

    lazy val ExpressionNoIn: Parser[Expr] = rep1sep(AssignmentExpressionNoIn, ",") ^^ { exprBlock(_) }

    lazy val LeftHandSideExpressionForIn = withPos(CallExpressionForIn | MemberExpressionForIn  )

    lazy val ElementList = opt(Elision) ~> rep1sep(AssignmentExpression, Elision)

    lazy val LabelledStatement: Parser[Statement] = withPos((Identifier <~ ":") ~ Stmt ^^ { case i ~ s => NamedStatement(i, s) })

    lazy val DefaultClause = withPos("default" ^^^ Ident(Names.DEFAULT)) ~ (":" ~> opt(Stmts)) ^^ { case d ~ stmts => d -> Block(stmts.toList.flatten) }

    lazy val BitwiseOROperator = "|"

    lazy val UnaryOperator = "delete" |
                             "void"   |
                             "typeof" |
                             "++"     |
                             "--"     |
                             "+"      |
                             "-"      |
                             "~"      |
                             "!"  

    lazy val RelationalOperator = "<="         |
                                  ">="         |
                                  "<"          |
                                  ">"          |
                                  "instanceof" |
                                  "in"

    lazy val ShiftExpression = withPos(AdditiveExpression * (ShiftOperator ^^ makeBinaryOp))

    lazy val Name = rep1sep(Identifier, ".")

    lazy val BitwiseANDOperator = "&"

    lazy val ArrayLiteral = withPos(
      "[" ~> opt(Elision) <~ "]"          ^^ { elems => devsearch.ast.ArrayLiteral(NoType, Nil, Nil, elems.toList.flatten) } |
      "[" ~> ElementList ~ Elision <~ "]" ^^ { case a ~ b => devsearch.ast.ArrayLiteral(NoType, Nil, Nil, a ++ b) } |
      "[" ~> opt(ElementList) <~ "]"      ^^ { elems => devsearch.ast.ArrayLiteral(NoType, Nil, Nil, elems.toList.flatten) })

    lazy val EqualityExpressionNoIn = withPos(RelationalExpressionNoIn * (EqualityOperator ^^ makeBinaryOp))

    lazy val ShiftOperator = ">>>" | ">>" | "<<"

    lazy val PropertyNameAndValue = withPos(
      (PropertyName <~ ":") ~ AssignmentExpression ^^ { case a ~ b => ValDef(NoModifiers, a, Nil, NoType, b) } |
      ("get" ~> PropertyName) ~ (("(" ~ ")") ~> StmtsBlock) ^^ { case n ~ block => FunctionDef(NoModifiers, n, Nil, Nil, Nil, NoType, block) } |
      ("set" ~> PropertyName) ~ ("(" ~> Identifier <~ ")") ~ StmtsBlock ^^ { case n ~ arg ~ block =>
        FunctionDef(NoModifiers, n, Nil, Nil, List(ValDef(NoModifiers, arg, Nil, NoType, NoExpr)), NoType, block)
      })

    lazy val LogicalORExpression = withPos(LogicalANDExpression * (LogicalOROperator ^^ makeBinaryOp))

    lazy val BitwiseXORExpression = withPos(BitwiseANDExpression * (BitwiseXOROperator ^^ makeBinaryOp))

    lazy val WithStatement: Parser[Statement] = withPos(("with" ~> "(" ~> Expression <~ ")") ~ Stmt ^^ {
      case e ~ s => FunctionCall(Ident("with"), Nil, List(e, s match {
        case e: Expr => e
        case s => Block(List(s)).fromAST(s)
      }))
    })

    lazy val IterationStatement: Parser[Statement] = withPos(
      ("do" ~> Stmt) ~ (("while" ~> "(") ~> Expression <~ (")" <~ opt(";"))) ^^ { case s ~ e => Do(e ,s) } |
      "while" ~> "(" ~> Expression ~ (")" ~> Stmt) ^^ { case e ~ s => While(e, s) } |
      (("for" ~> "(" ~> opt(ExpressionNoIn)) ~ (";" ~> opt(Expression) <~ ";") ~ (opt(Expression) <~ ")") ~ Stmt) ^^ {
        case e1 ~ e2 ~ e3 ~ s => For(Nil, e1.toList, e2 getOrElse NoExpr, e3.toList, s)
      } |
      (("for" ~> "(" ~> "var" ~> VariableDeclarationList) ~ (";" ~> opt(Expression)) ~ (";" ~> opt(Expression) <~ ")") ~ Stmt) ^^ {
        case decls ~ e2 ~ e3 ~ s => For(decls, Nil, e2 getOrElse NoExpr, e3.toList, s)
      } |
      (("for" ~> "(" ~> "var" ~> VariableDeclarationNoIn) ~ ("in" ~> Expression <~ ")") ~ Stmt) ^^ {
        case decl ~ e ~ s => Foreach(List(decl), e, s, false)
      } |
      (("for" ~> "(" ~> LeftHandSideExpressionForIn) ~ ("in" ~> Expression <~ ")") ~ Stmt) ^^ {
        case lhs ~ e ~ s => Foreach(List(ValDef(NoModifiers, Names.DEFAULT, Nil, NoType, NoExpr)), e, s match {
          case Block(ss) => Block(Assign(lhs, Ident(Names.DEFAULT), None) +: ss).fromAST(s)
          case _ => Block(Assign(lhs, Ident(Names.DEFAULT), None) :: s :: Nil)
        })
      })

    lazy val IfStatement: Parser[Expr] = withPos(("if" ~> "(" ~> Expression <~ ")") ~ Stmt ~ opt("else" ~> Stmt) ^^ {
      case cond ~ i ~ e => If(cond, i, e getOrElse NoStmt)
    })

    def makeBinaryOp(op: String) = (fact1: Expr, fact2: Expr) => BinaryOp(fact1, op, fact2)

    def stmtBlock(stmts: List[Statement]): Statement = stmts match {
      case Nil => Empty[Statement]
      case List(x) => x
      case ss => Block(ss)
    }

    def exprBlock(exprs: List[Expr]): Expr = exprs match {
      case Nil => Empty[Expr]
      case List(x) => x
      case ss => Block(ss)
    }

    def parse: AST = parseAll(Program, source.contents.mkString) match {
      case Success(result, _) => result
      case failure: NoSuccess => throw ParsingFailedError(
        new RuntimeException(failure.msg + " @ line %d:%d".format(failure.next.pos.line, failure.next.pos.column) + "\n" + failure.next.pos.longString)
      )
    }
  }
}
