package com.flowmatic.auth.workflow.expression;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Evaluates a small boolean expression language used by FILTER (predicate over a row) and CONDITION
 * (branch over the namespaced context). No arbitrary code execution.
 *
 * <pre>
 * expression := orExpr
 * orExpr     := andExpr ( 'or' andExpr )*
 * andExpr    := notExpr ( 'and' notExpr )*
 * notExpr    := 'not' notExpr | '(' expression ')' | comparison
 * comparison := operand ( op operand | 'is' ['not'] 'empty' )?
 * op         := '==' | '!=' | '>' | '<' | '>=' | '<=' | 'contains'
 * operand    := number | 'string' | "string" | true | false | path
 * </pre>
 *
 * <p>Paths resolve against the supplied scope (a row for FILTER, the context for CONDITION).
 * Numeric comparisons coerce string operands to numbers, so CSV values like {@code "5" > 4} work.
 */
@Component
public class ExpressionEvaluator {

  public boolean evaluate(String expression, Map<String, Object> scope) {
    if (expression == null || expression.isBlank()) {
      throw new ExpressionException("empty expression");
    }
    Parser parser = new Parser(tokenize(expression), scope);
    boolean result = parser.parseOr();
    parser.expectEnd();
    return result;
  }

  // ---- Tokenizer -----------------------------------------------------------

  private enum Type {
    NUMBER,
    STRING,
    IDENT,
    OP,
    LPAREN,
    RPAREN
  }

  private record Token(Type type, String text) {}

  private static List<Token> tokenize(String s) {
    List<Token> tokens = new ArrayList<>();
    int i = 0;
    int n = s.length();
    while (i < n) {
      char c = s.charAt(i);
      if (Character.isWhitespace(c)) {
        i++;
      } else if (c == '(') {
        tokens.add(new Token(Type.LPAREN, "("));
        i++;
      } else if (c == ')') {
        tokens.add(new Token(Type.RPAREN, ")"));
        i++;
      } else if (c == '\'' || c == '"') {
        int start = ++i;
        while (i < n && s.charAt(i) != c) {
          i++;
        }
        if (i >= n) {
          throw new ExpressionException("unterminated string literal");
        }
        tokens.add(new Token(Type.STRING, s.substring(start, i)));
        i++;
      } else if (c == '=' || c == '!' || c == '<' || c == '>') {
        if (i + 1 < n && s.charAt(i + 1) == '=') {
          tokens.add(new Token(Type.OP, "" + c + '='));
          i += 2;
        } else if (c == '<' || c == '>') {
          tokens.add(new Token(Type.OP, String.valueOf(c)));
          i++;
        } else {
          throw new ExpressionException("invalid operator near '" + c + "' (use == or !=)");
        }
      } else if (c == '-' && i + 1 < n && Character.isDigit(s.charAt(i + 1))) {
        int start = i++;
        while (i < n && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.')) {
          i++;
        }
        tokens.add(new Token(Type.NUMBER, s.substring(start, i)));
      } else if (Character.isDigit(c)) {
        int start = i;
        while (i < n && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.')) {
          i++;
        }
        tokens.add(new Token(Type.NUMBER, s.substring(start, i)));
      } else if (Character.isLetter(c) || c == '_') {
        int start = i;
        while (i < n
            && (Character.isLetterOrDigit(s.charAt(i))
                || s.charAt(i) == '_'
                || s.charAt(i) == '.')) {
          i++;
        }
        tokens.add(new Token(Type.IDENT, s.substring(start, i)));
      } else {
        throw new ExpressionException("unexpected character '" + c + "'");
      }
    }
    return tokens;
  }

  // ---- Parser / evaluator --------------------------------------------------

  private static final class Parser {
    private final List<Token> tokens;
    private final Map<String, Object> scope;
    private int pos;

    Parser(List<Token> tokens, Map<String, Object> scope) {
      this.tokens = tokens;
      this.scope = scope;
    }

    boolean parseOr() {
      boolean value = parseAnd();
      while (matchKeyword("or")) {
        boolean right = parseAnd();
        value = value || right;
      }
      return value;
    }

    private boolean parseAnd() {
      boolean value = parseNot();
      while (matchKeyword("and")) {
        boolean right = parseNot();
        value = value && right;
      }
      return value;
    }

    private boolean parseNot() {
      if (matchKeyword("not")) {
        return !parseNot();
      }
      if (peek() != null && peek().type() == Type.LPAREN) {
        next();
        boolean value = parseOr();
        expect(Type.RPAREN);
        return value;
      }
      return parseComparison();
    }

    private boolean parseComparison() {
      Object left = parseOperand();
      if (matchKeyword("is")) {
        boolean negate = matchKeyword("not");
        if (!matchKeyword("empty")) {
          throw new ExpressionException("expected 'empty' after 'is'");
        }
        boolean empty = isEmpty(left);
        return negate ? !empty : empty;
      }
      Token op = peek();
      if (op != null && op.type() == Type.OP) {
        next();
        Object right = parseOperand();
        return compare(left, op.text(), right);
      }
      if (op != null && op.type() == Type.IDENT && op.text().equalsIgnoreCase("contains")) {
        next();
        Object right = parseOperand();
        return containsOp(left, right);
      }
      return toBool(left);
    }

    private Object parseOperand() {
      Token t = next();
      if (t == null) {
        throw new ExpressionException("expected an operand");
      }
      return switch (t.type()) {
        case NUMBER -> Double.parseDouble(t.text());
        case STRING -> t.text();
        case IDENT -> {
          String text = t.text();
          if (text.equalsIgnoreCase("true")) {
            yield Boolean.TRUE;
          }
          if (text.equalsIgnoreCase("false")) {
            yield Boolean.FALSE;
          }
          if (isReservedWord(text)) {
            throw new ExpressionException("unexpected keyword '" + text + "'");
          }
          yield PathAccessor.resolve(scope, text).orElse(null);
        }
        default -> throw new ExpressionException("unexpected token '" + t.text() + "'");
      };
    }

    private boolean matchKeyword(String kw) {
      Token t = peek();
      if (t != null && t.type() == Type.IDENT && t.text().equalsIgnoreCase(kw)) {
        next();
        return true;
      }
      return false;
    }

    private static boolean isReservedWord(String w) {
      return w.equalsIgnoreCase("and")
          || w.equalsIgnoreCase("or")
          || w.equalsIgnoreCase("not")
          || w.equalsIgnoreCase("is")
          || w.equalsIgnoreCase("empty")
          || w.equalsIgnoreCase("contains");
    }

    private Token peek() {
      return pos < tokens.size() ? tokens.get(pos) : null;
    }

    private Token next() {
      return pos < tokens.size() ? tokens.get(pos++) : null;
    }

    private void expect(Type type) {
      Token t = next();
      if (t == null || t.type() != type) {
        throw new ExpressionException("expected " + type);
      }
    }

    void expectEnd() {
      if (pos != tokens.size()) {
        throw new ExpressionException("unexpected trailing tokens in expression");
      }
    }
  }

  // ---- Value operations ----------------------------------------------------

  private static boolean compare(Object a, String op, Object b) {
    return switch (op) {
      case "==" -> valuesEqual(a, b);
      case "!=" -> !valuesEqual(a, b);
      case ">", "<", ">=", "<=" -> order(a, op, b);
      default -> throw new ExpressionException("unknown operator '" + op + "'");
    };
  }

  private static boolean valuesEqual(Object a, Object b) {
    Double da = toDouble(a);
    Double db = toDouble(b);
    if (da != null && db != null) {
      return da.doubleValue() == db.doubleValue();
    }
    return String.valueOf(a).equals(String.valueOf(b));
  }

  private static boolean order(Object a, String op, Object b) {
    Double da = toDouble(a);
    Double db = toDouble(b);
    int cmp;
    if (da != null && db != null) {
      cmp = Double.compare(da, db);
    } else {
      cmp = String.valueOf(a).compareTo(String.valueOf(b));
    }
    return switch (op) {
      case ">" -> cmp > 0;
      case "<" -> cmp < 0;
      case ">=" -> cmp >= 0;
      case "<=" -> cmp <= 0;
      default -> throw new ExpressionException("unknown operator '" + op + "'");
    };
  }

  private static boolean containsOp(Object container, Object element) {
    if (container instanceof String s) {
      return s.contains(String.valueOf(element));
    }
    if (container instanceof Collection<?> c) {
      for (Object el : c) {
        if (valuesEqual(el, element)) {
          return true;
        }
      }
      return false;
    }
    return false;
  }

  private static Double toDouble(Object o) {
    if (o == null) {
      return null;
    }
    if (o instanceof Number num) {
      return num.doubleValue();
    }
    try {
      return Double.parseDouble(String.valueOf(o).trim());
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static boolean isEmpty(Object o) {
    if (o == null) {
      return true;
    }
    if (o instanceof String s) {
      return s.isEmpty();
    }
    if (o instanceof Collection<?> c) {
      return c.isEmpty();
    }
    if (o instanceof Map<?, ?> m) {
      return m.isEmpty();
    }
    return false;
  }

  private static boolean toBool(Object o) {
    if (o instanceof Boolean b) {
      return b;
    }
    if (o == null) {
      return false;
    }
    if (o instanceof Number num) {
      return num.doubleValue() != 0;
    }
    return Boolean.parseBoolean(String.valueOf(o));
  }
}
