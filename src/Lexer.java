import java.util.ArrayList;
import java.util.List;

/**
 * Lossless C# tokenizer. Handles comments, preprocessor lines, verbatim / raw /
 * interpolated strings (with nested holes), char literals, numbers, identifiers
 * (including '@' verbatim identifiers) and multi-character punctuators.
 *
 * '>' is never merged into '>>' or '>=' so that generic argument lists close
 * cleanly; since tokens are written back verbatim this has no effect on output.
 */
public final class Lexer {
    private final String src;
    private int pos = 0;
    private int line = 1;
    private final List<Token> out = new ArrayList<>();

    private static final String[] PUNCTUATORS = {
            "??=", "<<=", "?.", "??", "::", "=>", "==", "!=", "<=", "&&", "||", "++", "--",
            "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=", "<<", "->",
            "{", "}", "(", ")", "[", "]", ".", ",", ":", ";", "+", "-", "*", "/", "%", "&", "|", "^",
            "!", "~", "=", "<", ">", "?", "#", "$"
    };

    public Lexer(String src) {
        this.src = src;
    }

    public static List<Token> tokenize(String src) {
        Lexer lx = new Lexer(src);
        lx.lexRun(false, 0);
        lx.out.add(new Token(Token.Kind.EOF, "", lx.line));
        return lx.out;
    }

    private char peek(int off) {
        int p = pos + off;
        return p < src.length() ? src.charAt(p) : '\0';
    }

    private boolean startsWith(String s) {
        return src.startsWith(s, pos);
    }

    private void emit(Token.Kind kind, int start) {
        out.add(new Token(kind, src.substring(start, pos), line));
    }

    private void advance(int n) {
        for (int i = 0; i < n && pos < src.length(); i++) {
            if (src.charAt(pos) == '\n') line++;
            pos++;
        }
    }

    /**
     * Main lexing loop. When inHole is true, the loop stops (without consuming) at the
     * first ',' ':' or '}' at bracket depth zero, which terminates an interpolation
     * expression. holeBraces is the number of '}' characters that close the hole
     * (1 for "$", 2 for "$$" raw strings).
     */
    private void lexRun(boolean inHole, int holeBraces) {
        int depth = 0;
        boolean atLineStart = true;
        while (pos < src.length()) {
            char c = src.charAt(pos);
            int start = pos;

            if (inHole && depth == 0 && (c == ',' || c == ':' || c == '}')) {
                return;
            }

            // whitespace
            if (Character.isWhitespace(c) || c == '\uFEFF') {
                while (pos < src.length()) {
                    char w = src.charAt(pos);
                    if (!(Character.isWhitespace(w) || w == '\uFEFF')) break;
                    if (w == '\n') atLineStart = true;
                    advance(1);
                }
                emit(Token.Kind.WHITESPACE, start);
                continue;
            }

            // preprocessor directive
            if (c == '#' && atLineStart && !inHole) {
                while (pos < src.length() && src.charAt(pos) != '\n') advance(1);
                emit(Token.Kind.PREPROC, start);
                continue;
            }
            atLineStart = false;

            // comments
            if (c == '/' && peek(1) == '/') {
                while (pos < src.length() && src.charAt(pos) != '\n') advance(1);
                emit(Token.Kind.COMMENT, start);
                continue;
            }
            if (c == '/' && peek(1) == '*') {
                advance(2);
                while (pos < src.length() && !startsWith("*/")) advance(1);
                advance(2);
                emit(Token.Kind.COMMENT, start);
                continue;
            }

            // strings (regular, verbatim, raw, interpolated)
            if (c == '"' || ((c == '$' || c == '@') && looksLikeStringPrefix())) {
                lexString();
                continue;
            }

            // char literal
            if (c == '\'') {
                advance(1);
                while (pos < src.length() && src.charAt(pos) != '\'' && src.charAt(pos) != '\n') {
                    if (src.charAt(pos) == '\\') advance(1);
                    advance(1);
                }
                advance(1);
                emit(Token.Kind.CHAR, start);
                continue;
            }

            // numbers
            if (Character.isDigit(c) || (c == '.' && Character.isDigit(peek(1)))) {
                lexNumber();
                continue;
            }

            // identifiers / keywords
            if (Character.isLetter(c) || c == '_' || (c == '@' && (Character.isLetter(peek(1)) || peek(1) == '_'))) {
                if (c == '@') advance(1);
                while (pos < src.length() && isIdentPart(src.charAt(pos))) advance(1);
                String text = src.substring(start, pos);
                boolean verbatim = text.startsWith("@");
                if (!verbatim && Token.KEYWORDS.contains(text)) {
                    out.add(new Token(Token.Kind.KEYWORD, text, line));
                } else {
                    out.add(new Token(Token.Kind.IDENT, text, line));
                }
                continue;
            }

            // punctuators
            String p = null;
            for (String cand : PUNCTUATORS) {
                if (startsWith(cand)) {
                    p = cand;
                    break;
                }
            }
            if (p == null) {
                advance(1);
                emit(Token.Kind.PUNCT, start);
                continue;
            }
            advance(p.length());
            if (inHole) {
                if (p.equals("(") || p.equals("[") || p.equals("{")) depth++;
                else if (p.equals(")") || p.equals("]") || p.equals("}")) depth--;
            }
            emit(Token.Kind.PUNCT, start);
        }
    }

    private static boolean isIdentPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private boolean looksLikeStringPrefix() {
        int p = pos;
        while (p < src.length() && (src.charAt(p) == '$' || src.charAt(p) == '@')) p++;
        return p < src.length() && src.charAt(p) == '"' && p > pos;
    }

    private void lexNumber() {
        int start = pos;
        if (startsWith("0x") || startsWith("0X") || startsWith("0b") || startsWith("0B")) {
            advance(2);
            while (pos < src.length() && (Character.isLetterOrDigit(src.charAt(pos)) || src.charAt(pos) == '_')) advance(1);
            emit(Token.Kind.NUMBER, start);
            return;
        }
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (Character.isDigit(c) || c == '_') {
                advance(1);
            } else if (c == '.' && Character.isDigit(peek(1))) {
                advance(1);
            } else if ((c == 'e' || c == 'E') && (Character.isDigit(peek(1)) || ((peek(1) == '+' || peek(1) == '-') && Character.isDigit(peek(2))))) {
                advance(2);
            } else if ("uUlLfFdDmM".indexOf(c) >= 0) {
                advance(1);
            } else {
                break;
            }
        }
        emit(Token.Kind.NUMBER, start);
    }

    /** Lex a string literal starting at pos (prefix chars '$' / '@' included). */
    private void lexString() {
        int start = pos;
        int dollars = 0;
        boolean verbatim = false;
        while (pos < src.length() && (src.charAt(pos) == '$' || src.charAt(pos) == '@')) {
            if (src.charAt(pos) == '$') dollars++;
            else verbatim = true;
            advance(1);
        }
        // raw string literal: three or more quotes
        if (startsWith("\"\"\"")) {
            int quotes = 0;
            while (peek(0) == '"') {
                quotes++;
                advance(1);
            }
            lexRawString(start, quotes, dollars);
            return;
        }
        advance(1); // opening quote
        if (dollars == 0) {
            lexPlainString(start, verbatim);
        } else {
            lexInterpolated(start, verbatim, 1, null, 0);
        }
    }

    private void lexPlainString(int start, boolean verbatim) {
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (verbatim) {
                if (c == '"') {
                    if (peek(1) == '"') {
                        advance(2);
                        continue;
                    }
                    advance(1);
                    break;
                }
                advance(1);
            } else {
                if (c == '\\') {
                    advance(2);
                    continue;
                }
                if (c == '"') {
                    advance(1);
                    break;
                }
                if (c == '\n') break; // unterminated; give up gracefully
                advance(1);
            }
        }
        emit(Token.Kind.STRING, start);
    }

    /**
     * Interpolated string body (opening quote already consumed). Emits INTERP_START,
     * hole tokens, INTERP_MID..., INTERP_END. For raw interpolated strings closeQuotes
     * is the number of closing quotes and holeBraces the number of braces that open
     * a hole.
     */
    private void lexInterpolated(int start, boolean verbatim, int holeBraces, String closingQuotes, int rawQuotes) {
        boolean raw = closingQuotes != null;
        boolean first = true;
        int segStart = start;
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (raw) {
                if (startsWith(closingQuotes)) {
                    advance(rawQuotes);
                    // consume any extra quotes that belong to the literal's end
                    break;
                }
            } else if (verbatim) {
                if (c == '"') {
                    if (peek(1) == '"') {
                        advance(2);
                        continue;
                    }
                    advance(1);
                    break;
                }
            } else {
                if (c == '\\') {
                    advance(2);
                    continue;
                }
                if (c == '"') {
                    advance(1);
                    break;
                }
                if (c == '\n') break;
            }
            if (c == '{') {
                int braces = 0;
                while (peek(braces) == '{') braces++;
                if (braces < holeBraces || (braces >= 2 * holeBraces && !raw)) {
                    // escaped brace(s) '{{' (in non-raw strings) or literal braces in raw strings
                    advance(braces);
                    continue;
                }
                if (raw && braces > holeBraces) {
                    // extra braces are literal content; only the last holeBraces open the hole
                    advance(braces - holeBraces);
                }
                advance(holeBraces);
                out.add(new Token(first ? Token.Kind.INTERP_START : Token.Kind.INTERP_MID, src.substring(segStart, pos), line));
                first = false;
                // hole expression
                lexRun(true, holeBraces);
                // alignment / format specifier / closing brace(s): literal text until the closing brace(s)
                segStart = pos;
                int nested = 0;
                while (pos < src.length()) {
                    char h = src.charAt(pos);
                    if (h == '}' ) {
                        if (nested == 0) {
                            advance(holeBraces);
                            break;
                        }
                        nested--;
                    } else if (h == '{') {
                        nested++;
                    } else if (h == '"' && !raw) {
                        // format specifier can't contain quotes; treat as string end safety
                        break;
                    }
                    advance(1);
                }
                continue;
            }
            if (c == '}' && !raw && peek(1) == '}') {
                advance(2);
                continue;
            }
            advance(1);
        }
        out.add(new Token(first ? Token.Kind.STRING : Token.Kind.INTERP_END, src.substring(segStart, pos), line));
    }

    private void lexRawString(int start, int quotes, int dollars) {
        StringBuilder q = new StringBuilder();
        for (int i = 0; i < quotes; i++) q.append('"');
        String closing = q.toString();
        if (dollars == 0) {
            while (pos < src.length() && !startsWith(closing)) advance(1);
            advance(quotes);
            emit(Token.Kind.STRING, start);
        } else {
            lexInterpolated(start, false, dollars, closing, quotes);
        }
    }
}
