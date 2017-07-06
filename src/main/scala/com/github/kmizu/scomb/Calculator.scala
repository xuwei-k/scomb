package com.github.kmizu.scomb

import jdk.nashorn.internal.codegen.CompilerConstants.Call

object Calculator extends SCombinator[Int] {
  def root: Parser[Int] = expression
  def expression: Parser[Int] = A
  def A: Parser[Int] = M.chainl {
    $("+").map{op => (lhs: Int, rhs: Int) => lhs + rhs} |
    $("-").map{op => (lhs: Int, rhs: Int) => lhs - rhs}
  }
  def M: Parser[Int] = P.chainl {
    $("*").map{op => (lhs: Int, rhs: Int) => lhs * rhs} |
    $("/").map{op => (lhs: Int, rhs: Int) => lhs / rhs}
  }
  def P: Parser[Int] = (for {
    _ <- string("("); e <- expression; _ <- string(")") } yield e) | number
  def number: Parser[Int] = oneOf('0'to'9').*.map{digits => digits.mkString.toInt}

  def main(args: Array[String]): Unit = {
    println(Calculator.parseAll("1+2*3"))
    println(Calculator.parseAll("1+5*3/4"))
    println(Calculator.parseAll("(1+5)*3/2"))
    println(Calculator.parseAll("(1-5) *3/2"))
  }
}
