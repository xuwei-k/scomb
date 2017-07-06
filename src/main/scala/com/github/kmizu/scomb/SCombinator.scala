package com.github.kmizu.scomb

abstract class SCombinator[R] {self =>
  case class ~[A, B](a: A, b: B)

  protected var input: String = ""

  protected var recent: Option[Failure] = None

  lazy val spacing: Parser[String] = (
    $(" ") | $("\t") | $("\b") | $("\f") | $("\r\n") | $("\r") | $("\n")
  )

  def token(symbol: String): Parser[String] = for {
    s <- $(symbol)
    _ <- spacing
  } yield s

  sealed abstract class ParseResult[+T] {
    def index: Int
  }
  sealed abstract class ParseFailure extends ParseResult[Nothing]

  case class Success[+T](value: T, override val index: Int) extends ParseResult[T]
  case class Failure(message: String, override val index: Int) extends ParseFailure {
    self.recent match {
      case None => self.recent = Some(this)
      case Some(failure) if index > failure.index => self.recent = Some(this)
      case _ => // Do nothing
    }
  }
  case class Fatal(message: String, override val index: Int) extends ParseFailure

  def root: Parser[R]

  def isEOF(index: Int): Boolean = index >= input.length

  def current(index: Int): String = input.substring(index)

  final def parse(input: String): ParseResult[R] = synchronized {
    this.input = input
    root(0) match {
      case s@Success(_, _) => s
      case f@Failure(_, _) => recent.get
      case f@Fatal(_,  _) => f
    }
  }

  final def parseAll(input: String): ParseResult[R] = synchronized {
    parse(input) match {
      case s@Success(_, i) =>
        if(isEOF(i)) s else Failure("input remains: " + current(i), i)
      case otherwise => otherwise
    }
  }



  type Parser[+T] = Int => ParseResult[T]

  def oneOf(seqs: Seq[Char]*): Parser[String] = index => {
    if(isEOF(index) || !seqs.exists(seq => seq.exists(ch => ch == current(index).charAt(0))))
      Failure(s"expected: ${seqs.mkString("[", ",", "]")}", index)
    else Success(current(index).substring(0, 1), index + 1)
  }

  def string(literal: String): Parser[String] = index => {
    if(isEOF(index)) {
      Failure(s"expected: ${literal} actual: EOF", index)
    } else if(current(index).startsWith(literal)) {
      Success(literal, index + literal.length)
    } else {
      Failure(s"expected: ${literal} actual: ${current(index).substring(0, literal.length)}", index)
    }
  }

  def $(literal: String): Parser[String] = string(literal)

  def predict[A](cases: (Char, Parser[A])*): Parser[A] = index => {
    def newFailureMessage(head: Char, cases: Map[Char, Parser[A]]): String = {
      val expectation = cases.map{ case (ch, _) => ch}.mkString("[", ",", "]")
      s"expect: ${expectation} actual: ${head}"
    }

    if(isEOF(index)) {
      val map: Map[Char, Parser[A]] = Map(cases :_*)
      Failure(newFailureMessage(0, map), index)
    } else {
      val head = current(index).charAt(0)
      val map = Map(cases:_*)
      map.get(head) match {
        case Some(clause) => clause(index)
        case None =>
          Failure(newFailureMessage(head, map), index)
      }
    }
  }

  implicit class RichParser[T](val self: Parser[T]) {
    def * : Parser[List[T]] = index => {
      def repeat(index: Int): (List[T], Int) = self(index) match {
        case Success(value, next1) =>
          val (result, next2) = repeat(next1)
          (value::result, next2)
        case Failure(message, next) =>
          (Nil, next)
        case Fatal(_, _) =>
          (Nil, -1)
      }
      val (result, next) = repeat(index)
      if(next >= 0) {
        Success(result, next)
      } else {
        Fatal("fatal error", next)
      }
    }

    def repeat1By(separator: Parser[Any]): Parser[List[T]] = {
      self ~ (separator ~ self).* ^^ { case b ~ bs =>
          bs.foldLeft(b::Nil) { case (bs, _ ~ b) =>
              b::bs
          }.reverse
      }
    }

    def repeat0By(separator: Parser[Any]): Parser[List[T]] = {
      self.repeat1By(separator).? ^^ {
        case None => Nil
        case Some(list) => list
      }
    }

    def ~[U](right: Parser[U]) : Parser[T ~ U] = index => {
      self(index) match {
        case Success(value1, next1) =>
          right(next1) match {
            case Success(value2, next2) =>
              Success(new ~(value1, value2), next2)
            case failure@Failure(_, _) =>
              failure
            case fatal@Fatal(_, _) =>
              fatal
          }
        case failure@Failure(message, next) =>
          failure
        case fatal@Fatal(_, _) =>
          fatal
      }
    }

    def ? : Parser[Option[T]] = index => {
      self(index) match {
        case Success(v, i) => Success(Some(v), i)
        case Failure(message, i) => Success(None, i)
        case fatal@Fatal(_, _) => fatal
      }
    }

    def chainl(q: Parser[(T, T) => T]): Parser[T] = {
      (self ~ (q ~ self).*).map { case x ~ xs =>
          xs.foldLeft(x) { case (a, f ~ b) =>
              f(a, b)
          }
      }
    }

    def |(right: Parser[T]): Parser[T] = index => {
      self(index) match {
        case success@Success(_, _) => success
        case Failure(_, _) => right(index)
        case fatal@Fatal(_, _) => fatal
      }
    }

    def filter(predicate: T => Boolean): Parser[T] = index => {
      self(index) match {
        case Success(value, next) =>
          if(predicate(value))
            Success(value, next)
          else
            Failure("not matched to predicate", index)
        case failure@Failure(_, _) =>
          failure
        case fatal@Fatal(_, _) =>
          fatal
      }
    }

    def withFilter(predicate: T => Boolean): Parser[T] = filter(predicate)

    def map[U](function: T => U): Parser[U] = index => {
      self(index) match {
        case Success(value, next) => Success(function(value), next)
        case failure@Failure(_, _) => failure
        case fatal@Fatal(_, _) => fatal
      }
    }

    def ^^[U](function: T => U): Parser[U] = map(function)

    def flatMap[U](function: T => Parser[U]): Parser[U] = index => {
      self(index) match {
        case Success(value, next) =>
          function(value)(next)
        case failure@Failure(_, _) =>
          failure
        case fatal@Fatal(_, _) =>
          fatal
      }
    }
  }
}