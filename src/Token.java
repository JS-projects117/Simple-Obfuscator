import java.util.Set;

/**
 * A single lexical token of a C# source file.
 *
 * Every character of the source is covered by exactly one token (trivia such as
 * whitespace, comments and preprocessor lines are tokens too), so the file can
 * be reproduced verbatim by concatenating {@link #text} of all tokens.
 *
 * Interpolated strings are split into INTERP_START / (hole tokens) / INTERP_MID /
 * ... / INTERP_END so that identifiers inside interpolation holes are ordinary
 * tokens that can be analysed and renamed like any other code.
 */
public final class Token {
    public enum Kind {
        IDENT, KEYWORD, NUMBER, STRING, CHAR,
        INTERP_START, INTERP_MID, INTERP_END,
        PUNCT, WHITESPACE, COMMENT, PREPROC, EOF
    }

    public final Kind kind;
    /** Text as it appears in the output. Mutable: renaming changes it. */
    public String text;
    /** Identifier name without a leading '@' (only meaningful for IDENT). */
    public final String value;
    public final int line;
    /** Index in the file's significant (non-trivia) token list, or -1 for trivia. */
    public int index = -1;

    public Token(Kind kind, String text, int line) {
        this.kind = kind;
        this.text = text;
        this.line = line;
        this.value = kind == Kind.IDENT && text.startsWith("@") ? text.substring(1) : text;
    }

    public boolean isTrivia() {
        return kind == Kind.WHITESPACE || kind == Kind.COMMENT || kind == Kind.PREPROC;
    }

    public boolean is(String s) {
        return (kind == Kind.PUNCT || kind == Kind.KEYWORD || kind == Kind.IDENT) && text.equals(s);
    }

    public boolean isIdent() {
        return kind == Kind.IDENT;
    }

    public boolean isIdent(String name) {
        return kind == Kind.IDENT && value.equals(name);
    }

    public boolean isKeyword(String kw) {
        return kind == Kind.KEYWORD && text.equals(kw);
    }

    public boolean isOpener() {
        return kind == Kind.INTERP_START || (kind == Kind.PUNCT && (text.equals("(") || text.equals("[") || text.equals("{")));
    }

    public boolean isCloser() {
        return kind == Kind.INTERP_END || (kind == Kind.PUNCT && (text.equals(")") || text.equals("]") || text.equals("}")));
    }

    /** True for '.', '?.', '::' and '->': the token before a member name. */
    public boolean isMemberAccessOp() {
        return kind == Kind.PUNCT && (text.equals(".") || text.equals("?.") || text.equals("::") || text.equals("->"));
    }

    /** Built-in type keywords that can start a type. */
    public boolean isBuiltinType() {
        return kind == Kind.KEYWORD && BUILTIN_TYPES.contains(text);
    }

    @Override
    public String toString() {
        return kind + "(" + text + ")@" + line;
    }

    public static final Set<String> BUILTIN_TYPES = Set.of(
            "bool", "byte", "sbyte", "char", "decimal", "double", "float", "int", "uint",
            "long", "ulong", "short", "ushort", "object", "string", "void");

    public static final Set<String> KEYWORDS = Set.of(
            "abstract", "as", "base", "bool", "break", "byte", "case", "catch", "char", "checked",
            "class", "const", "continue", "decimal", "default", "delegate", "do", "double", "else",
            "enum", "event", "explicit", "extern", "false", "finally", "fixed", "float", "for",
            "foreach", "goto", "if", "implicit", "in", "int", "interface", "internal", "is", "lock",
            "long", "namespace", "new", "null", "object", "operator", "out", "override", "params",
            "private", "protected", "public", "readonly", "ref", "return", "sbyte", "sealed",
            "short", "sizeof", "stackalloc", "static", "string", "struct", "switch", "this", "throw",
            "true", "try", "typeof", "uint", "ulong", "unchecked", "unsafe", "ushort", "using",
            "virtual", "void", "volatile", "while");

    /** Contextual keywords: lexed as identifiers but never renamed and never treated as user names. */
    public static final Set<String> CONTEXTUAL_KEYWORDS = Set.of(
            "add", "alias", "and", "ascending", "async", "await", "by", "descending", "dynamic",
            "equals", "from", "get", "global", "group", "init", "into", "join", "let", "managed",
            "nameof", "nint", "nuint", "not", "notnull", "on", "or", "orderby", "partial", "record",
            "remove", "required", "scoped", "select", "set", "unmanaged", "value", "var", "when",
            "where", "with", "yield", "file", "args", "_");
}
