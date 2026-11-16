package org.codelabuk.perf.expr

import scala.util.Try

/**
 * A tiny, safe condition language for YAML (`pollUntil`, `if`):
 *
 *   #{status} != 'PENDING'
 *   #{attempts} < 5 and not #{done}
 *   exists #{token}
 *   #{roles} contains 'admin' or (#{code} == 200 && #{body.size()} > 0)
 *
 * Operands: #{...} (any Gatling EL), 'quoted' / "quoted" strings, numbers, true/false, bare words.
 * Operators: == != < <= > >= contains, and/&&, or/||, not/!, exists, parentheses.
 * Numbers compare numerically when both sides are numeric, otherwise as strings.
 */
sealed trait Condition {
  /** `resolve` receives the EL body of a #{...} reference and returns its value, if present. */
  def eval(resolve: String => Option[Any]): Boolean
  def source: String
}

object Condition {

  sealed trait Operand
  final case class Var(el: String) extends Operand
  final case class Lit(value: String) extends Operand

  private final case class Or(l: Condition, r: Condition, source: String) extends Condition {
    def eval(f: String => Option[Any]): Boolean = l.eval(f) || r.eval(f)
  }
  private final case class And(l: Condition, r: Condition, source: String) extends Condition {
    def eval(f: String => Option[Any]): Boolean = l.eval(f) && r.eval(f)
  }
  private final case class Not(c: Condition, source: String) extends Condition {
    def eval(f: String => Option[Any]): Boolean = !c.eval(f)
  }
  private final case class Exists(o: Operand, source: String) extends Condition {
    def eval(f: String => Option[Any]): Boolean = value(o, f).isDefined
  }
  private final case class Truthy(o: Operand, source: String) extends Condition {
    def eval(f: String => Option[Any]): Boolean = value(o, f) match {
      case None                       => false
      case Some(b: Boolean)           => b
      case Some(s: Iterable[_])       => s.nonEmpty
      case Some(v) =>
        val s = v.toString.trim
        s.nonEmpty && !s.equalsIgnoreCase("false") && s != "0"
    }
  }
  private final case class Compare(l: Operand, op: String, r: Operand, source: String) extends Condition {
    def eval(f: String => Option[Any]): Boolean =
      (value(l, f), value(r, f)) match {
        case (Some(a), Some(b)) => compare(a, op, b)
        case (None, None)       => op == "=="
        case _                  => op == "!="
      }
  }

  private def value(o: Operand, f: String => Option[Any]): Option[Any] = o match {
    case Lit(v) => Some(v)
    case Var(e) => f(e).flatMap {
        case null    => None
        case None    => None
        case Some(x) => Option(x)
        case x       => Some(x)
      }
  }

  private def num(a: Any): Option[BigDecimal] = a match {
    case n: Int    => Some(BigDecimal(n))
    case n: Long   => Some(BigDecimal(n))
    case n: Double => Some(BigDecimal(n))
    case n: java.lang.Number => Try(BigDecimal(n.toString)).toOption
    case s: String => Try(BigDecimal(s.trim)).toOption
    case _         => None
  }

  private def compare(a: Any, op: String, b: Any): Boolean = op match {
    case "contains" =>
      a match {
        case it: Iterable[_] => it.exists(x => x != null && x.toString == b.toString)
        case m: java.util.Collection[_] => m.toArray.exists(x => x != null && x.toString == b.toString)
        case other => other.toString.contains(b.toString)
      }
    case _ =>
      val c: Int = (num(a), num(b)) match {
        case (Some(x), Some(y)) => x.compare(y)
        case _                  => a.toString.compareTo(b.toString)
      }
      op match {
        case "==" => c == 0
        case "!=" => c != 0
        case "<"  => c < 0
        case "<=" => c <= 0
        case ">"  => c > 0
        case ">=" => c >= 0
      }
  }

  // ------------------------------------------------------------------ parser

  final class ParseException(msg: String) extends IllegalArgumentException(msg)

  private sealed trait Tok
  private final case class TVar(el: String) extends Tok
  private final case class TStr(v: String) extends Tok
  private final case class TWord(v: String) extends Tok
  private final case class TOp(v: String) extends Tok
  private case object LParen extends Tok
  private case object RParen extends Tok

  private val Ops = List("==", "!=", "<=", ">=", "&&", "||", "<", ">", "!")

  private def tokenize(s: String): List[Tok] = {
    val out = List.newBuilder[Tok]
    var i = 0
    while (i < s.length) {
      val c = s.charAt(i)
      if (c.isWhitespace) i += 1
      else if (c == '(') { out += LParen; i += 1 }
      else if (c == ')') { out += RParen; i += 1 }
      else if (s.startsWith("#{", i)) {
        // EL may itself contain parentheses, e.g. #{list.size()} - find matching brace
        val end = s.indexOf('}', i)
        if (end < 0) throw new ParseException(s"unclosed #{ in condition: $s")
        out += TVar(s.substring(i + 2, end).trim); i = end + 1
      } else if (c == '\'' || c == '"') {
        val end = s.indexOf(c.toInt, i + 1)
        if (end < 0) throw new ParseException(s"unclosed quote in condition: $s")
        out += TStr(s.substring(i + 1, end)); i = end + 1
      } else Ops.find(s.startsWith(_, i)) match {
        case Some(op) => out += TOp(op); i += op.length
        case None =>
          val start = i
          while (i < s.length && !s.charAt(i).isWhitespace && !"()'\"<>=!&|".contains(s.charAt(i))) i += 1
          if (i == start) throw new ParseException(s"unexpected '${s.charAt(i)}' in condition: $s")
          out += TWord(s.substring(start, i))
      }
    }
    out.result()
  }

  def parse(text: String): Condition = {
    val toks = tokenize(text)
    if (toks.isEmpty) throw new ParseException("empty condition")
    var pos = 0
    def peek: Option[Tok] = toks.lift(pos)
    def next(): Tok = { val t = toks.lift(pos).getOrElse(throw new ParseException(s"unexpected end of condition: $text")); pos += 1; t }
    def isWord(t: Option[Tok], w: String) = t.exists { case TWord(v) => v.equalsIgnoreCase(w); case _ => false }

    def orE(): Condition = {
      var l = andE()
      while (isWord(peek, "or") || peek.contains(TOp("||"))) { next(); l = Or(l, andE(), text) }
      l
    }
    def andE(): Condition = {
      var l = notE()
      while (isWord(peek, "and") || peek.contains(TOp("&&"))) { next(); l = And(l, notE(), text) }
      l
    }
    def notE(): Condition =
      if (isWord(peek, "not") || peek.contains(TOp("!"))) { next(); Not(notE(), text) }
      else primary()
    def operand(): Operand = next() match {
      case TVar(e)  => Var(e)
      case TStr(v)  => Lit(v)
      case TWord(v) => Lit(v)
      case t        => throw new ParseException(s"expected a value but found $t in condition: $text")
    }
    def primary(): Condition =
      if (peek.contains(LParen)) {
        next(); val c = orE()
        if (next() != RParen) throw new ParseException(s"missing ')' in condition: $text")
        c
      } else if (isWord(peek, "exists")) { next(); Exists(operand(), text) }
      else {
        val l = operand()
        peek match {
          case Some(TOp(op)) if Set("==", "!=", "<", "<=", ">", ">=")(op) => next(); Compare(l, op, operand(), text)
          case Some(TWord(w)) if w.equalsIgnoreCase("contains")            => next(); Compare(l, "contains", operand(), text)
          case _                                                           => Truthy(l, text)
        }
      }

    val c = orE()
    if (pos != toks.length) throw new ParseException(s"unexpected '${toks(pos)}' in condition: $text")
    c
  }
}
