import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Structural parser: discovers namespaces, using directives, type declarations
 * (merging partials), members, parameters and block-scoped locals. It is not a
 * full C# parser; it understands enough of the declaration grammar to attribute
 * every identifier token to the declaration it belongs to, and skips over
 * expressions using bracket matching.
 */
public final class Parser {
    public static final class ParseException extends RuntimeException {
        public ParseException(String msg) {
            super(msg);
        }
    }

    private static final Set<String> MODIFIERS = Set.of(
            "public", "private", "protected", "internal", "static", "readonly", "const", "volatile",
            "virtual", "override", "abstract", "sealed", "extern", "unsafe", "new", "partial", "async",
            "required", "fixed", "ref");

    private static final Set<String> TYPE_KEYWORDS = Set.of("class", "struct", "interface", "enum", "delegate");

    private static final Set<String> PARAM_MODIFIERS = Set.of("ref", "out", "in", "params", "this", "scoped", "readonly");

    private final Model.Project project;
    private final Model.CsFile f;
    private final List<Token> sig;
    private int i = 0;

    private Parser(Model.Project project, Model.CsFile f) {
        this.project = project;
        this.f = f;
        this.sig = f.sig;
    }

    public static void parse(Model.Project project, Model.CsFile f) {
        Prepass.run(project, f);
        Parser p = new Parser(project, f);
        p.parseFile();
        p.scanLocals();
    }

    // ------------------------------------------------------------------ helpers

    private Token tok(int idx) {
        if (idx < 0) return sig.get(sig.size() - 1); // EOF token
        return sig.get(Math.min(idx, sig.size() - 1));
    }

    private Token cur() {
        return tok(i);
    }

    private boolean atEnd() {
        return cur().kind == Token.Kind.EOF;
    }

    private ParseException error(String msg) {
        Token t = cur();
        return new ParseException(f.relativePath + ":" + t.line + ": " + msg + " (near '" + t.text + "')");
    }

    private int matchOf(int idx) {
        int m = f.match[idx];
        if (m < 0) throw new ParseException(f.relativePath + ":" + tok(idx).line + ": unbalanced '" + tok(idx).text + "'");
        return m;
    }

    private void markType(int start, int end) {
        for (int k = start; k <= end && k < sig.size(); k++) {
            if (tok(k).isIdent()) f.typeCtx[k] = true;
        }
    }

    private void fillType(int start, int end, Model.TypeDecl t) {
        for (int k = start; k <= end && k < sig.size(); k++) f.typeOf[k] = t;
    }

    private void fillMember(int start, int end, Model.MemberDecl m) {
        for (int k = start; k <= end && k < sig.size(); k++) f.memberOf[k] = m;
    }

    /** Skip forward to the first token at depth 0 that satisfies the predicate; returns its index (or size). */
    private int skipTo(int from, java.util.function.Predicate<Token> stop) {
        int k = from;
        while (k < sig.size()) {
            Token t = tok(k);
            if (t.kind == Token.Kind.EOF) return k;
            if (stop.test(t)) return k;
            if (t.isOpener()) {
                k = f.match[k] >= 0 ? f.match[k] + 1 : k + 1;
                continue;
            }
            if (t.isCloser()) return k; // unexpected closer: let caller deal with it
            k++;
        }
        return k;
    }

    // ------------------------------------------------------------------ file level

    private String currentNamespace = "";

    private void parseFile() {
        while (!atEnd()) {
            parseNamespaceMember(-1);
        }
    }

    /** Parse one item at namespace level. closeBrace is the index of the enclosing '}' or -1. */
    private void parseNamespaceMember(int closeBrace) {
        Token t = cur();
        if (t.isKeyword("using") || (t.isIdent("global") && tok(i + 1).isKeyword("using"))) {
            parseUsing();
        } else if (t.isKeyword("namespace")) {
            parseNamespace();
        } else if (t.isKeyword("extern")) {
            i = skipTo(i, x -> x.is(";")) + 1;
        } else if (t.is("[") && isGlobalAttribute(i)) {
            i = matchOf(i) + 1;
        } else if (isTypeDeclStart(i)) {
            parseTypeDecl(null);
        } else if (t.is(";")) {
            i++;
        } else {
            // Top-level statements or unexpected tokens: skip conservatively.
            if (t.isOpener()) i = matchOf(i) + 1;
            else i++;
        }
    }

    private boolean isGlobalAttribute(int idx) {
        Token a = tok(idx + 1);
        return a.isIdent() && tok(idx + 2).is(":") && (a.isIdent("assembly") || a.isIdent("module"));
    }

    private void parseUsing() {
        if (cur().isIdent("global")) i++;
        i++; // using
        boolean isStatic = false;
        if (cur().isKeyword("static")) {
            isStatic = true;
            i++;
        }
        int start = i;
        // alias?
        if (cur().isIdent() && tok(i + 1).is("=")) {
            String alias = cur().value;
            f.nsCtx[i] = true;
            i += 2;
            int tStart = i;
            int semi = skipTo(i, x -> x.is(";"));
            f.aliases.put(alias, new int[]{tStart, semi - 1});
            markType(tStart, semi - 1);
            i = semi + 1;
            return;
        }
        int semi = skipTo(i, x -> x.is(";"));
        if (isStatic) {
            f.usingStatics.add(new int[]{start, semi - 1});
            markType(start, semi - 1);
        } else {
            StringBuilder sb = new StringBuilder();
            for (int k = start; k < semi; k++) {
                Token t = tok(k);
                if (t.isIdent()) {
                    f.nsCtx[k] = true;
                    sb.append(t.value);
                } else if (t.is(".")) {
                    sb.append('.');
                }
            }
            String ns = sb.toString();
            f.usings.add(ns);
            project.addNamespace(ns);
        }
        i = semi + 1;
    }

    private void parseNamespace() {
        i++; // namespace
        StringBuilder sb = new StringBuilder();
        while (cur().isIdent() || cur().is(".")) {
            if (cur().isIdent()) {
                f.nsCtx[i] = true;
                sb.append(cur().value);
            } else {
                sb.append('.');
            }
            i++;
        }
        String saved = currentNamespace;
        currentNamespace = saved.isEmpty() ? sb.toString() : saved + "." + sb;
        project.addNamespace(currentNamespace);
        if (cur().is(";")) {
            // file-scoped namespace
            i++;
            while (!atEnd()) parseNamespaceMember(-1);
            currentNamespace = saved;
            return;
        }
        if (!cur().is("{")) throw error("expected '{' after namespace name");
        int close = matchOf(i);
        i++;
        while (i < close) {
            parseNamespaceMember(close);
        }
        i = close + 1;
        currentNamespace = saved;
    }

    // ------------------------------------------------------------------ attributes & modifiers

    private static final class AttrInfo {
        final Set<String> names = new HashSet<>();
        boolean fieldSerialized;
    }

    private AttrInfo parseAttributes() {
        AttrInfo info = new AttrInfo();
        while (cur().is("[") && f.match[i] >= 0 && !isGlobalAttribute(i)) {
            int close = matchOf(i);
            int k = i + 1;
            while (k < close) {
                String target = null;
                if (tok(k).isIdent() && tok(k + 1).is(":")) {
                    target = tok(k).value;
                    k += 2;
                }
                // attribute name chain
                int nameStart = k;
                String last = null;
                while (tok(k).isIdent() || tok(k).is(".") || tok(k).is("::")) {
                    if (tok(k).isIdent()) last = tok(k).value;
                    k++;
                }
                if (last != null) {
                    markType(nameStart, k - 1);
                    if (last.endsWith("Attribute") && last.length() > 9) last = last.substring(0, last.length() - 9);
                    info.names.add(last);
                    if ("field".equals(target) && (last.equals("SerializeField") || last.equals("SerializeReference"))) {
                        info.fieldSerialized = true;
                    }
                }
                if (tok(k).is("<") && f.genericMatch[k] >= 0) k = f.genericMatch[k] + 1;
                if (tok(k).is("(")) k = matchOf(k) + 1;
                if (tok(k).is(",")) k++;
                else if (k < close) k++; // recovery
            }
            i = close + 1;
        }
        return info;
    }

    private Set<String> parseModifiers() {
        Set<String> mods = new HashSet<>();
        while (true) {
            Token t = cur();
            if (t.kind == Token.Kind.KEYWORD && MODIFIERS.contains(t.text)) {
                // 'new' as modifier only if followed by something that is not '(' / '{' (never at member start anyway)
                mods.add(t.text);
                i++;
            } else if (t.isIdent() && (t.value.equals("partial") || t.value.equals("async") || t.value.equals("required"))
                    && (tok(i + 1).isIdent() || tok(i + 1).kind == Token.Kind.KEYWORD)) {
                mods.add(t.value);
                i++;
            } else {
                break;
            }
        }
        return mods;
    }

    private boolean isTypeDeclStart(int idx) {
        int k = idx;
        while (tok(k).is("[") && f.match[k] >= 0) k = f.match[k] + 1;
        while (true) {
            Token t = tok(k);
            if ((t.kind == Token.Kind.KEYWORD && MODIFIERS.contains(t.text))
                    || t.isIdent("partial") || t.isIdent("async") || t.isIdent("required")) {
                k++;
            } else {
                break;
            }
        }
        Token t = tok(k);
        if (t.kind == Token.Kind.KEYWORD && TYPE_KEYWORDS.contains(t.text)) return true;
        return t.isIdent("record") && (tok(k + 1).isIdent() || tok(k + 1).isKeyword("struct") || tok(k + 1).isKeyword("class"));
    }

    // ------------------------------------------------------------------ types

    private void parseTypeDecl(Model.TypeDecl outer) {
        int start = i;
        AttrInfo attrs = parseAttributes();
        Set<String> mods = parseModifiers();
        parseTypeDeclAfterModifiers(outer, start, attrs, mods);
    }

    private void parseTypeDeclAfterModifiers(Model.TypeDecl outer, int start, AttrInfo attrs, Set<String> mods) {
        String kind;
        Token t = cur();
        if (t.isIdent("record")) {
            kind = "record";
            i++;
            if (cur().isKeyword("struct") || cur().isKeyword("class")) i++;
        } else if (t.kind == Token.Kind.KEYWORD && TYPE_KEYWORDS.contains(t.text)) {
            kind = t.text;
            i++;
        } else {
            throw error("expected type declaration");
        }

        if (kind.equals("delegate")) {
            parseDelegate(outer, start, attrs, mods);
            return;
        }

        if (!cur().isIdent()) throw error("expected type name");
        int nameTok = i;
        String name = cur().value;
        i++;

        List<String> typeParams = parseTypeParamDecl();

        Model.TypeDecl decl = new Model.TypeDecl();
        decl.name = name;
        decl.kind = kind;
        decl.namespace = outer != null ? "" : currentNamespace;
        decl.outer = outer;
        decl.typeParams.addAll(typeParams);
        Model.TypeDecl existing = project.findType(decl.fullName(), typeParams.size());
        if (existing != null && (existing.modifiers.contains("partial") || mods.contains("partial"))) {
            decl = existing;
        } else if (existing != null) {
            // duplicate non-partial type (e.g. same class under different #if branches): merge anyway
            decl = existing;
        } else {
            project.addType(decl);
            if (outer != null) outer.nested.add(decl);
            else f.types.add(decl);
        }
        decl.modifiers.addAll(mods);
        decl.attributes.addAll(attrs.names);
        decl.sites.add(new Model.DeclSite(f, nameTok));

        // record primary constructor
        if (cur().is("(")) {
            int close = matchOf(i);
            List<Model.Param> ps = parseParamList(i + 1, close);
            for (Model.Param p : ps) {
                Model.MemberDecl m = new Model.MemberDecl();
                m.name = p.name;
                m.kind = Model.MemberKind.PROPERTY;
                m.owner = decl;
                m.file = f;
                m.nameTok = p.nameTok;
                m.type = p.type;
                m.modifiers.add("public");
                m.attributes.add("RecordPositional");
                m.rangeStart = p.nameTok;
                m.rangeEnd = p.nameTok;
                decl.members.add(m);
            }
            i = close + 1;
        }

        // base list
        if (cur().is(":")) {
            i++;
            while (!atEnd()) {
                Model.TypeRange tr = parseTypeTokens();
                if (tr == null) throw error("expected base type");
                decl.bases.add(tr);
                if (cur().is("(")) i = matchOf(i) + 1; // record base constructor call
                if (cur().is(",")) {
                    i++;
                    continue;
                }
                break;
            }
        }
        parseConstraints(decl.constraints);

        if (cur().is(";")) { // e.g. partial declaration without body (rare) or record without body
            i++;
            return;
        }
        if (!cur().is("{")) throw error("expected '{' for type body");
        int open = i;
        int close = matchOf(open);
        fillType(start, close, decl);
        i = open + 1;
        if (kind.equals("enum")) {
            parseEnumBody(decl, close);
        } else {
            while (i < close) {
                int before = i;
                parseMember(decl, close);
                if (i == before) i++; // guarantee progress
            }
        }
        i = close + 1;
        if (cur().is(";")) i++;
    }

    private void parseDelegate(Model.TypeDecl outer, int start, AttrInfo attrs, Set<String> mods) {
        Model.TypeRange ret = parseTypeTokens();
        if (ret == null || !cur().isIdent()) throw error("expected delegate name");
        int nameTok = i;
        String name = cur().value;
        i++;
        List<String> typeParams = parseTypeParamDecl();
        Model.TypeDecl decl = new Model.TypeDecl();
        decl.name = name;
        decl.kind = "delegate";
        decl.namespace = outer != null ? "" : currentNamespace;
        decl.outer = outer;
        decl.typeParams.addAll(typeParams);
        decl.modifiers.addAll(mods);
        decl.attributes.addAll(attrs.names);
        decl.sites.add(new Model.DeclSite(f, nameTok));
        if (project.findType(decl.fullName(), typeParams.size()) == null) {
            project.addType(decl);
            if (outer != null) outer.nested.add(decl);
            else f.types.add(decl);
        }
        // Represent the delegate signature as an "Invoke" method so parameters get scoped.
        Model.MemberDecl m = new Model.MemberDecl();
        m.name = "Invoke";
        m.kind = Model.MemberKind.METHOD;
        m.owner = decl;
        m.file = f;
        m.type = ret;
        m.modifiers.add("public");
        m.attributes.add("DelegateInvoke");
        if (!cur().is("(")) throw error("expected delegate parameter list");
        int close = matchOf(i);
        m.params.addAll(parseParamList(i + 1, close));
        m.scopeRanges.add(new int[]{i, close});
        i = close + 1;
        parseConstraints(m.constraints);
        int semi = skipTo(i, x -> x.is(";"));
        m.rangeStart = start;
        m.rangeEnd = semi;
        fillType(start, semi, decl);
        fillMember(start, semi, m);
        decl.members.add(m);
        i = semi + 1;
    }

    private List<String> parseTypeParamDecl() {
        List<String> names = new ArrayList<>();
        if (!cur().is("<")) return names;
        int close = f.genericMatch[i] >= 0 ? f.genericMatch[i] : findClosingAngle(i);
        for (int k = i + 1; k < close; k++) {
            Token t = tok(k);
            if (t.is("[") && f.match[k] >= 0) {
                k = f.match[k];
                continue;
            }
            if (t.isIdent() && !t.isIdent("in") && !t.isIdent("out")) {
                if (tok(k + 1).is(",") || k + 1 == close) names.add(t.value);
            }
        }
        i = close + 1;
        return names;
    }

    private int findClosingAngle(int open) {
        int depth = 0;
        for (int k = open; k < sig.size(); k++) {
            if (tok(k).is("<")) depth++;
            else if (tok(k).is(">")) {
                depth--;
                if (depth == 0) return k;
            }
        }
        throw error("unbalanced '<'");
    }

    private void parseConstraints(java.util.Map<String, List<Model.TypeRange>> into) {
        while (cur().isIdent("where") && tok(i + 1).isIdent() && tok(i + 2).is(":")) {
            String tp = tok(i + 1).value;
            i += 3;
            List<Model.TypeRange> list = into.computeIfAbsent(tp, k -> new ArrayList<>());
            while (!atEnd()) {
                Token t = cur();
                if (t.isKeyword("class") || t.isKeyword("struct") || t.isIdent("notnull") || t.isIdent("unmanaged") || t.isKeyword("default")) {
                    i++;
                } else if (t.isKeyword("new")) {
                    i++;
                    if (cur().is("(")) i = matchOf(i) + 1;
                } else {
                    Model.TypeRange tr = parseTypeTokens();
                    if (tr == null) break;
                    list.add(tr);
                }
                if (cur().is("?")) i++;
                if (cur().is(",")) {
                    i++;
                    continue;
                }
                break;
            }
        }
    }

    private void parseEnumBody(Model.TypeDecl decl, int close) {
        while (i < close) {
            int start = i;
            AttrInfo attrs = parseAttributes();
            if (!cur().isIdent()) {
                i++;
                continue;
            }
            Model.MemberDecl m = new Model.MemberDecl();
            m.name = cur().value;
            m.kind = Model.MemberKind.ENUM_MEMBER;
            m.owner = decl;
            m.file = f;
            m.nameTok = i;
            m.attributes.addAll(attrs.names);
            m.modifiers.add("public");
            i++;
            if (cur().is("=")) {
                i = skipTo(i + 1, x -> x.is(","));
            }
            m.rangeStart = start;
            m.rangeEnd = i - 1;
            fillMember(start, i - 1, m);
            decl.members.add(m);
            if (cur().is(",")) i++;
        }
    }

    // ------------------------------------------------------------------ members

    private void parseMember(Model.TypeDecl owner, int close) {
        int start = i;
        AttrInfo attrs = parseAttributes();
        Set<String> mods = parseModifiers();
        Token t = cur();

        if (t.is(";")) {
            i++;
            return;
        }
        if (isTypeDeclStart(i) && !(t.isKeyword("new") && !mods.isEmpty())) {
            parseTypeDeclAfterModifiers(owner, start, attrs, mods);
            return;
        }

        Model.MemberDecl m = new Model.MemberDecl();
        m.owner = owner;
        m.file = f;
        m.modifiers.addAll(mods);
        m.attributes.addAll(attrs.names);
        m.fieldAttributeSerialized = attrs.fieldSerialized;

        if (t.isKeyword("event")) {
            i++;
            m.kind = Model.MemberKind.EVENT;
            m.type = parseTypeTokens();
            if (m.type == null) throw error("expected event type");
            parseNameChain(m);
            if (cur().is("{")) {
                int c = matchOf(i);
                m.scopeRanges.add(new int[]{i, c});
                i = c + 1;
            } else {
                // possibly several names
                List<Model.MemberDecl> extra = new ArrayList<>();
                while (cur().is(",") && tok(i + 1).isIdent()) {
                    i++;
                    Model.MemberDecl e = new Model.MemberDecl();
                    e.owner = owner;
                    e.file = f;
                    e.kind = Model.MemberKind.EVENT;
                    e.modifiers.addAll(mods);
                    e.attributes.addAll(attrs.names);
                    e.type = m.type;
                    e.name = cur().value;
                    e.nameTok = i;
                    i++;
                    extra.add(e);
                }
                int semi = skipTo(i, x -> x.is(";"));
                i = semi + 1;
                for (Model.MemberDecl e : extra) {
                    e.rangeStart = e.nameTok;
                    e.rangeEnd = e.nameTok;
                    owner.members.add(e);
                }
            }
            finishMember(m, start, owner);
            return;
        }

        if (t.is("~")) { // destructor
            i++;
            m.kind = Model.MemberKind.DTOR;
            m.name = cur().isIdent() ? cur().value : "~";
            m.nameTok = i;
            i++;
            parseParamsAndBody(m);
            finishMember(m, start, owner);
            return;
        }

        if (t.isKeyword("implicit") || t.isKeyword("explicit")) {
            i++;
            if (!cur().isKeyword("operator")) throw error("expected 'operator'");
            i++;
            m.kind = Model.MemberKind.OPERATOR;
            m.name = "operator";
            m.type = parseTypeTokens();
            parseParamsAndBody(m);
            finishMember(m, start, owner);
            return;
        }

        // constructor
        if (t.isIdent(owner.name) && tok(i + 1).is("(")) {
            m.kind = Model.MemberKind.CTOR;
            m.name = owner.name;
            m.nameTok = i;
            i++;
            int close2 = matchOf(i);
            m.params.addAll(parseParamList(i + 1, close2));
            m.scopeRanges.add(new int[]{i, close2});
            i = close2 + 1;
            if (cur().is(":")) { // : base(...) / : this(...)
                i++;
                if (cur().isKeyword("base") || cur().isKeyword("this")) i++;
                if (cur().is("(")) {
                    int c = matchOf(i);
                    m.scopeRanges.add(new int[]{i, c});
                    i = c + 1;
                }
            }
            parseBody(m);
            finishMember(m, start, owner);
            return;
        }

        Model.TypeRange type = parseTypeTokens();
        if (type == null) {
            // unknown construct; skip one token for progress
            i++;
            return;
        }
        m.type = type;
        t = cur();

        if (t.isKeyword("operator")) {
            i++;
            m.kind = Model.MemberKind.OPERATOR;
            m.name = "operator";
            while (!cur().is("(") && !atEnd()) i++;
            parseParamsAndBody(m);
            finishMember(m, start, owner);
            return;
        }

        if (t.isKeyword("this") && tok(i + 1).is("[")) {
            m.kind = Model.MemberKind.INDEXER;
            m.name = "this";
            i++;
            int c = matchOf(i);
            m.params.addAll(parseParamList(i + 1, c));
            m.scopeRanges.add(new int[]{i, c});
            i = c + 1;
            parseAccessorsOrExpr(m);
            finishMember(m, start, owner);
            return;
        }

        // explicit interface implementation of an indexer: IFoo.this[...]
        if (t.isIdent() && chainEndsWithThis(i)) {
            int k = i;
            while (!tok(k).isKeyword("this")) k++;
            markType(i, k - 1);
            m.explicitInterfaceImpl = true;
            m.kind = Model.MemberKind.INDEXER;
            m.name = "this";
            i = k + 1;
            int c = matchOf(i);
            m.params.addAll(parseParamList(i + 1, c));
            m.scopeRanges.add(new int[]{i, c});
            i = c + 1;
            parseAccessorsOrExpr(m);
            finishMember(m, start, owner);
            return;
        }

        if (!t.isIdent()) {
            i++;
            return;
        }
        parseNameChain(m);
        t = cur();

        if (t.is("<") || t.is("(")) {
            m.kind = Model.MemberKind.METHOD;
            if (t.is("<")) m.typeParams.addAll(parseTypeParamDecl());
            if (!cur().is("(")) throw error("expected '(' after method name");
            int c = matchOf(i);
            m.params.addAll(parseParamList(i + 1, c));
            m.hasParamsArray = m.params.stream().anyMatch(p -> p.type != null && p.type.text().startsWith("params"));
            m.scopeRanges.add(new int[]{i, c});
            i = c + 1;
            parseConstraints(m.constraints);
            parseBody(m);
            finishMember(m, start, owner);
            return;
        }
        if (t.is("{") || t.is("=>")) {
            m.kind = Model.MemberKind.PROPERTY;
            parseAccessorsOrExpr(m);
            finishMember(m, start, owner);
            return;
        }
        // field(s)
        m.kind = Model.MemberKind.FIELD;
        List<Model.MemberDecl> fields = new ArrayList<>();
        fields.add(m);
        Model.MemberDecl currentField = m;
        while (true) {
            if (cur().is("=")) {
                int exprStart = i + 1;
                int end = skipTo(exprStart, x -> x.is(",") || x.is(";"));
                if (end > exprStart) currentField.scopeRanges.add(new int[]{exprStart, end - 1});
                i = end;
            }
            if (cur().is(",") && tok(i + 1).isIdent()) {
                i++;
                Model.MemberDecl e = new Model.MemberDecl();
                e.owner = owner;
                e.file = f;
                e.kind = Model.MemberKind.FIELD;
                e.modifiers.addAll(mods);
                e.attributes.addAll(attrs.names);
                e.type = m.type;
                e.name = cur().value;
                e.nameTok = i;
                e.rangeStart = i;
                i++;
                fields.add(e);
                currentField = e;
                continue;
            }
            break;
        }
        int semi = skipTo(i, x -> x.is(";"));
        i = semi + 1;
        for (Model.MemberDecl fd : fields) {
            if (fd == m) {
                finishMember(fd, start, owner);
            } else {
                fd.rangeEnd = semi;
                fillMember(fd.rangeStart, fd.rangeEnd, fd);
                owner.members.add(fd);
            }
        }
    }

    private boolean chainEndsWithThis(int idx) {
        int k = idx;
        while (tok(k).isIdent() || tok(k).is(".") || tok(k).is("::") || (tok(k).is("<") && f.genericMatch[k] >= 0)) {
            if (tok(k).is("<")) k = f.genericMatch[k] + 1;
            else k++;
        }
        return tok(k).isKeyword("this") && tok(k - 1).is(".");
    }

    /** Parse member name, possibly qualified by an explicit interface (IFoo.Bar or IFoo<T>.Bar). */
    private void parseNameChain(Model.MemberDecl m) {
        int chainStart = i;
        int last = i;
        while (true) {
            if (!tok(i).isIdent()) throw error("expected member name");
            last = i;
            i++;
            if (tok(i).is("<") && f.genericMatch[i] >= 0 && (tok(f.genericMatch[i] + 1).is(".") || tok(f.genericMatch[i] + 1).is("::"))) {
                i = f.genericMatch[i] + 1;
            }
            if ((tok(i).is(".") || tok(i).is("::")) && tok(i + 1).isIdent()) {
                i++;
                continue;
            }
            break;
        }
        if (last > chainStart) {
            m.explicitInterfaceImpl = true;
            markType(chainStart, last - 2);
        }
        m.name = tok(last).value;
        m.nameTok = last;
    }

    private void parseParamsAndBody(Model.MemberDecl m) {
        if (!cur().is("(")) throw error("expected '('");
        int c = matchOf(i);
        m.params.addAll(parseParamList(i + 1, c));
        m.scopeRanges.add(new int[]{i, c});
        i = c + 1;
        parseBody(m);
    }

    /** Method / ctor body: block, expression body or ';'. */
    private void parseBody(Model.MemberDecl m) {
        if (cur().is("{")) {
            int c = matchOf(i);
            m.scopeRanges.add(new int[]{i, c});
            i = c + 1;
        } else if (cur().is("=>")) {
            int exprStart = i + 1;
            int semi = skipTo(exprStart, x -> x.is(";"));
            m.scopeRanges.add(new int[]{exprStart, semi - 1});
            i = semi + 1;
        } else if (cur().is(";")) {
            i++;
        } else {
            throw error("expected method body");
        }
    }

    /** Property / indexer: accessor block (with optional initializer) or expression body. */
    private void parseAccessorsOrExpr(Model.MemberDecl m) {
        if (cur().is("{")) {
            int c = matchOf(i);
            m.scopeRanges.add(new int[]{i, c});
            i = c + 1;
            if (cur().is("=")) {
                int exprStart = i + 1;
                int semi = skipTo(exprStart, x -> x.is(";"));
                m.scopeRanges.add(new int[]{exprStart, semi - 1});
                i = semi + 1;
            }
        } else if (cur().is("=>")) {
            int exprStart = i + 1;
            int semi = skipTo(exprStart, x -> x.is(";"));
            m.scopeRanges.add(new int[]{exprStart, semi - 1});
            i = semi + 1;
        } else {
            throw error("expected property body");
        }
    }

    private void finishMember(Model.MemberDecl m, int start, Model.TypeDecl owner) {
        m.rangeStart = start;
        m.rangeEnd = i - 1;
        fillMember(start, i - 1, m);
        owner.members.add(m);
    }

    /** Parse a parameter list between open+1 and close (exclusive). */
    private List<Model.Param> parseParamList(int from, int close) {
        List<Model.Param> list = new ArrayList<>();
        int saved = i;
        i = from;
        while (i < close) {
            parseAttributes();
            while ((cur().kind == Token.Kind.KEYWORD && PARAM_MODIFIERS.contains(cur().text)) || cur().isIdent("scoped")) i++;
            Model.TypeRange tr = parseTypeTokens();
            if (tr != null && cur().isIdent()) {
                Model.Param p = new Model.Param();
                p.type = tr;
                p.name = cur().value;
                p.nameTok = i;
                list.add(p);
                i++;
            } else if (tr != null && (cur().is(",") || i >= close)) {
                // parameter without a name (delegate declarations may omit names? no - but be lenient)
            } else {
                // recovery: skip to next comma
            }
            int next = skipTo(i, x -> x.is(","));
            if (next > close) next = close;
            i = Math.min(next, close);
            if (cur().is(",")) i++;
        }
        i = saved;
        return list;
    }

    /**
     * Parse a type at the cursor: [ref|readonly|in|out|params] (tuple | builtin | qualified name with generics)
     * followed by array / nullable / pointer suffixes. Advances the cursor. Returns null when no type
     * starts here.
     */
    private Model.TypeRange parseTypeTokens() {
        int start = i;
        while ((cur().kind == Token.Kind.KEYWORD && (cur().text.equals("ref") || cur().text.equals("readonly") || cur().text.equals("in") || cur().text.equals("out") || cur().text.equals("params")))
                || cur().isIdent("scoped")) {
            i++;
        }
        Token t = cur();
        if (t.is("(")) {
            if (f.match[i] < 0) return null;
            int c = f.match[i];
            // tuple type: contains at least one "type name" pair or is empty
            markTupleTypes(i + 1, c);
            i = c + 1;
        } else if (t.isBuiltinType()) {
            i++;
        } else if (t.isIdent() && !t.isIdent("where") && !t.isIdent("when")) {
            if (t.isIdent("global") && tok(i + 1).is("::")) i += 2;
            while (true) {
                if (!cur().isIdent()) {
                    i = start;
                    return null;
                }
                f.typeCtx[i] = true;
                i++;
                if (cur().is("<") && f.genericMatch[i] >= 0) {
                    markType(i, f.genericMatch[i]);
                    i = f.genericMatch[i] + 1;
                }
                if ((cur().is(".") || cur().is("::")) && tok(i + 1).isIdent()) {
                    i++;
                    continue;
                }
                break;
            }
        } else {
            return null;
        }
        // suffixes
        while (true) {
            if (cur().is("?")) {
                i++;
            } else if (cur().is("[") && f.match[i] >= 0 && isArraySpecifier(i)) {
                i = f.match[i] + 1;
            } else if (cur().is("*")) {
                i++;
            } else {
                break;
            }
        }
        return new Model.TypeRange(f, start, i - 1);
    }

    private void markTupleTypes(int from, int close) {
        int k = from;
        while (k < close) {
            int saved = i;
            i = k;
            Model.TypeRange tr = parseTypeTokens();
            if (tr == null) {
                i = saved;
                k = skipTo(k + 1, x -> x.is(",")) + 1;
                continue;
            }
            if (cur().isIdent()) i++; // element name
            k = skipTo(i, x -> x.is(",")) + 1;
            i = saved;
        }
    }

    private boolean isArraySpecifier(int open) {
        int c = f.match[open];
        for (int k = open + 1; k < c; k++) {
            if (!tok(k).is(",")) return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ locals

    private static final Set<String> NOT_TYPE_ENDING_IDENTS = Set.of(
            "await", "yield", "async", "from", "select", "where", "orderby", "let", "join", "into",
            "group", "by", "equals", "on", "ascending", "descending", "when", "and", "or", "not",
            "with", "init", "get", "set", "add", "remove", "value", "nameof", "global", "partial", "record");

    private void scanLocals() {
        for (Model.TypeDecl t : allTypes()) {
            for (Model.MemberDecl m : t.members) {
                if (m.file != f) continue;
                scanMemberLocals(m);
            }
        }
    }

    private List<Model.TypeDecl> allTypes() {
        List<Model.TypeDecl> out = new ArrayList<>();
        for (Model.TypeDecl t : project.typesByKey.values()) collectTypes(t, out);
        return out;
    }

    private void collectTypes(Model.TypeDecl t, List<Model.TypeDecl> out) {
        if (!out.contains(t)) out.add(t);
    }

    private void scanMemberLocals(Model.MemberDecl m) {
        // parameters: visible in the whole member
        for (Model.Param p : m.params) {
            Model.LocalVar lv = new Model.LocalVar();
            lv.name = p.name;
            lv.declTok = p.nameTok;
            lv.scopeStart = m.rangeStart;
            lv.scopeEnd = m.rangeEnd;
            lv.typeRange = p.type;
            lv.isParam = true;
            m.locals.add(lv);
        }
        for (int[] range : m.scopeRanges) {
            scanRange(m, range[0], range[1]);
        }
        m.locals.sort((a, b) -> Integer.compare(a.declTok, b.declTok));
    }

    private boolean isTypeEnding(int idx) {
        Token t = tok(idx);
        if (t.isIdent()) {
            return !NOT_TYPE_ENDING_IDENTS.contains(t.value) || t.value.equals("var") || t.value.equals("dynamic");
        }
        if (t.isBuiltinType()) return !t.text.equals("void") || true;
        if (t.is(">")) return f.genericMatch[idx] >= 0;
        if (t.is("]")) {
            int open = f.match[idx];
            return open >= 0 && isArraySpecifier(open) && open > 0 && isTypeEnding(open - 1);
        }
        if (t.is("?")) {
            return idx > 0 && (tok(idx - 1).isIdent() || tok(idx - 1).isBuiltinType()
                    || (tok(idx - 1).is(">") && f.genericMatch[idx - 1] >= 0) || tok(idx - 1).is("]")) && isTypeEnding(idx - 1);
        }
        return false;
    }

    private boolean isDeclFollow(Token t) {
        // Note: ':' is deliberately excluded — "a < b ? c : d" would otherwise look like a
        // nullable-typed declaration "b? c" (since '?' counts as a type ending).
        return t.is("=") || t.is(";") || t.is(",") || t.is(")") || t.isKeyword("in") || t.is("=>")
                || t.isIdent("when");
    }

    /** Walk backwards from the last token of a type to its first token. */
    private int typeStartBackward(int end) {
        int j = end;
        while (j >= 0) {
            Token t = tok(j);
            if (t.is("?") || t.is("*")) {
                j--;
            } else if (t.is("]") && f.match[j] >= 0) {
                j = f.match[j] - 1;
            } else if (t.is(">") && f.genericMatch[j] >= 0) {
                j = f.genericMatch[j] - 1;
            } else if (t.isIdent() || t.isBuiltinType()) {
                j--;
                if (j >= 0 && (tok(j).is(".") || tok(j).is("::")) && j > 0 && tok(j - 1).isIdent()) {
                    j--;
                    continue;
                }
                break;
            } else {
                break;
            }
        }
        return j + 1;
    }

    private boolean isStatementStart(int typeStart) {
        if (typeStart == 0) return true;
        Token p = tok(typeStart - 1);
        if (p.is(";") || p.is("{") || p.is("}") || p.is(":")) return true;
        if (p.isKeyword("const") || p.isKeyword("using") || p.isKeyword("ref") || p.isKeyword("readonly") || p.isIdent("await")) return true;
        if (p.is("(")) {
            Token b = tok(typeStart - 2);
            return b.isKeyword("for") || b.isKeyword("foreach") || b.isKeyword("using") || b.isKeyword("fixed");
        }
        return false;
    }

    private void scanRange(Model.MemberDecl m, int start, int end) {
        for (int idx = start; idx <= end; idx++) {
            Token t = tok(idx);
            if (!t.isIdent() || Token.CONTEXTUAL_KEYWORDS.contains(t.value)) {
                // 'var (a, b)' deconstruction
                if (t.isIdent("var") && tok(idx + 1).is("(") && f.match[idx + 1] >= 0) {
                    int c = f.match[idx + 1];
                    for (int k = idx + 2; k < c; k++) {
                        if (tok(k).isIdent() && !tok(k).isIdent("_") && (tok(k + 1).is(",") || tok(k + 1).is(")"))) {
                            declare(m, k, null, null, false, false);
                        }
                    }
                }
                continue;
            }
            if (alreadyDeclared(m, idx)) continue;
            Token prev = tok(idx - 1);
            Token next = tok(idx + 1);

            // untyped lambda parameter: x => ...
            if (next.is("=>") && !isTypeEnding(idx - 1) && !prev.is(")")) {
                declare(m, idx, null, null, false, false);
                continue;
            }
            // untyped lambda parameter list: (a, b) => ...
            if ((prev.is("(") || prev.is(",")) && (next.is(",") || next.is(")"))) {
                int open = enclosingParen(idx);
                if (open >= 0 && f.match[open] >= 0 && tok(f.match[open] + 1).is("=>") && allSimpleIdents(open, f.match[open])) {
                    declare(m, idx, null, null, false, false);
                    continue;
                }
            }
            // typed declaration: Type name ...
            if (idx > start && isTypeEnding(idx - 1) && !prevIsExcludedKeyword(idx - 1)) {
                int typeStart = typeStartBackward(idx - 1);
                if (typeStart > idx - 1) continue;
                Token before = tok(typeStart - 1);
                if (before.isMemberAccessOp() || before.isKeyword("new") || before.isKeyword("is") || before.isKeyword("as")
                        || before.isKeyword("typeof") || before.isKeyword("case")) {
                    // 'is Foo x', 'case Foo x' are still declarations; 'new Foo x' is not valid
                    if (before.isKeyword("new") || before.isMemberAccessOp()) continue;
                }
                if (isDeclFollow(next)) {
                    boolean isVar = tok(typeStart).isIdent("var") && typeStart == idx - 1;
                    Model.TypeRange tr = isVar ? null : new Model.TypeRange(f, typeStart, idx - 1);
                    markType(typeStart, idx - 1);
                    Model.LocalVar lv = declare(m, idx, tr, null, false, false);
                    if (lv != null) {
                        if (next.isKeyword("in")) {
                            int close = skipTo(idx + 2, x -> x.is(")"));
                            lv.initRange = new int[]{idx + 2, close - 1};
                            lv.isForeach = true;
                        } else if (next.is("=") && isVar) {
                            int e = skipTo(idx + 2, x -> x.is(",") || x.is(";") || x.is(")"));
                            lv.initRange = new int[]{idx + 2, e - 1};
                        }
                    }
                    if (isStatementStart(typeStart) && !next.isKeyword("in")) {
                        // comma-separated declarators: int a = 1, b = 2;
                        int k = idx + 1;
                        while (k <= end) {
                            int e = skipTo(k, x -> x.is(",") || x.is(";") || x.is(")"));
                            if (!tok(e).is(",")) break;
                            k = e + 1;
                            if (tok(k).isIdent() && (tok(k + 1).is("=") || tok(k + 1).is(",") || tok(k + 1).is(";"))) {
                                Model.LocalVar lv2 = declare(m, k, tr, null, false, false);
                                if (lv2 != null && isVar && tok(k + 1).is("=")) {
                                    int e2 = skipTo(k + 2, x -> x.is(",") || x.is(";") || x.is(")"));
                                    lv2.initRange = new int[]{k + 2, e2 - 1};
                                }
                                k++;
                            } else {
                                break;
                            }
                        }
                    }
                    continue;
                }
                // local function: Type name(...) { ... } at statement start
                if (next.is("(") && isStatementStart(typeStart) && f.match[idx + 1] >= 0) {
                    int after = f.match[idx + 1] + 1;
                    Token a = tok(after);
                    if (a.is("{") || a.is("=>") || a.isIdent("where")) {
                        markType(typeStart, idx - 1);
                        declare(m, idx, new Model.TypeRange(f, typeStart, idx - 1), null, true, false);
                        continue;
                    }
                }
            }
        }
    }

    private boolean prevIsExcludedKeyword(int idx) {
        Token p = tok(idx);
        return p.kind == Token.Kind.KEYWORD && !p.isBuiltinType();
    }

    private boolean allSimpleIdents(int open, int close) {
        boolean expectIdent = true;
        for (int k = open + 1; k < close; k++) {
            Token t = tok(k);
            if (expectIdent) {
                if (!t.isIdent()) return false;
                expectIdent = false;
            } else {
                if (!t.is(",")) return false;
                expectIdent = true;
            }
        }
        return !expectIdent || close == open + 1;
    }

    /** Innermost unclosed '(' before idx (within the same scope). */
    private int enclosingParen(int idx) {
        int k = f.enclosing[idx];
        while (k >= 0 && !tok(k).is("(")) k = f.enclosing[k];
        return k;
    }

    private boolean alreadyDeclared(Model.MemberDecl m, int idx) {
        for (Model.LocalVar lv : m.locals) {
            if (lv.declTok == idx) return true;
        }
        return false;
    }

    private Model.LocalVar declare(Model.MemberDecl m, int nameTok, Model.TypeRange type, int[] init, boolean localFunction, boolean isParam) {
        if (alreadyDeclared(m, nameTok)) return null;
        Token nt = tok(nameTok);
        if (!nt.isIdent() || nt.isIdent("_")) return null;
        Model.LocalVar lv = new Model.LocalVar();
        lv.name = nt.value;
        lv.declTok = nameTok;
        lv.typeRange = type;
        lv.initRange = init;
        lv.isLocalFunction = localFunction;
        lv.isParam = isParam;
        // scope: innermost enclosing block within the member
        int block = f.enclosing[nameTok];
        while (block >= 0 && block >= m.rangeStart && !tok(block).is("{")) block = f.enclosing[block];
        if (block >= m.rangeStart && block >= 0 && tok(block).is("{")) {
            lv.scopeStart = localFunction ? block : nameTok;
            lv.scopeEnd = f.match[block];
        } else {
            lv.scopeStart = localFunction ? m.rangeStart : nameTok;
            lv.scopeEnd = m.rangeEnd;
        }
        m.locals.add(lv);
        return lv;
    }

    // ------------------------------------------------------------------ prepass

    /** Computes bracket matching, generic argument matching and context flags for a file. */
    static final class Prepass {
        private static final Pattern WORD = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

        static void run(Model.Project project, Model.CsFile f) {
            int n = f.sig.size();
            f.match = new int[n];
            f.genericMatch = new int[n];
            f.enclosing = new int[n];
            f.typeCtx = new boolean[n];
            f.nsCtx = new boolean[n];
            f.namedArg = new boolean[n];
            f.initBrace = new boolean[n];
            f.typeOf = new Model.TypeDecl[n];
            f.memberOf = new Model.MemberDecl[n];
            java.util.Arrays.fill(f.match, -1);
            java.util.Arrays.fill(f.genericMatch, -1);
            java.util.Arrays.fill(f.enclosing, -1);

            // brackets
            java.util.ArrayDeque<Integer> stack = new java.util.ArrayDeque<>();
            for (int k = 0; k < n; k++) {
                Token t = f.sig.get(k);
                f.enclosing[k] = stack.isEmpty() ? -1 : stack.peek();
                if (t.isOpener()) {
                    stack.push(k);
                } else if (t.isCloser()) {
                    String want = closerFor(t);
                    // pop until matching opener kind
                    while (!stack.isEmpty()) {
                        int o = stack.pop();
                        if (openerText(f.sig.get(o)).equals(want)) {
                            f.match[o] = k;
                            f.match[k] = o;
                            f.enclosing[k] = stack.isEmpty() ? -1 : stack.peek();
                            break;
                        }
                    }
                }
            }

            // identifiers and string words
            for (Token t : f.sig) {
                if (t.isIdent()) project.allIdentifiers.add(t.value);
                if (t.kind == Token.Kind.STRING || t.kind == Token.Kind.INTERP_START || t.kind == Token.Kind.INTERP_MID
                        || t.kind == Token.Kind.INTERP_END) {
                    Matcher m = WORD.matcher(t.text);
                    while (m.find()) project.stringWords.add(m.group());
                }
            }

            // generic argument lists
            for (int k = 0; k < n; k++) {
                if (f.sig.get(k).is("<") && k > 0 && f.sig.get(k - 1).isIdent() && f.genericMatch[k] < 0) {
                    tryGeneric(f, k);
                }
            }

            // heuristic contexts
            for (int k = 0; k < n; k++) {
                Token t = f.sig.get(k);
                if (!t.isIdent()) continue;
                Token prev = k > 0 ? f.sig.get(k - 1) : null;
                Token next = k + 1 < n ? f.sig.get(k + 1) : null;
                if (prev != null && next != null) {
                    if (next.is(":") && (prev.is("(") || prev.is(",") || prev.is("["))) {
                        f.namedArg[k] = true;
                        project.namedArguments.add(t.value);
                    }
                    if (next.is(":") && (prev.is(";") || prev.is("{") || prev.is("}"))) {
                        f.namedArg[k] = true; // label
                    }
                }
                if (prev != null && (prev.isKeyword("new") || prev.isKeyword("is") || prev.isKeyword("as")
                        || (prev.is("(") && k > 1 && (f.sig.get(k - 2).isKeyword("typeof") || f.sig.get(k - 2).isKeyword("default") || f.sig.get(k - 2).isKeyword("sizeof"))))) {
                    markChain(f, k);
                }
                // generic args content
                if (prev != null && (prev.is("<") || prev.is(",")) ) {
                    int e = f.enclosing[k];
                    // enclosing[] only tracks brackets; check generic pair by scanning back
                    int lt = enclosingGeneric(f, k);
                    if (lt >= 0) markChain(f, k);
                }
                // cast: ( Type ) operand
                if (prev != null && prev.is("(") && f.match[k - 1] >= 0) {
                    int close = f.match[k - 1];
                    if (isTypeChain(f, k, close - 1) && close + 1 < n && isCastOperand(f.sig.get(close + 1))) {
                        for (int j = k; j < close; j++) if (f.sig.get(j).isIdent()) f.typeCtx[j] = true;
                    }
                }
            }

            // object initializer braces
            for (int k = 0; k < n; k++) {
                Token t = f.sig.get(k);
                if (!t.is("{") || k == 0) continue;
                int p = k - 1;
                Token pt = f.sig.get(p);
                if (pt.is(")") && f.match[p] >= 0) p = f.match[p] - 1;
                if (p >= 0 && (f.sig.get(p).isIdent() || f.sig.get(p).is(">") || f.sig.get(p).is("]"))) {
                    int cs = chainStart(f, p);
                    if (cs > 0 && f.sig.get(cs - 1).isKeyword("new")) f.initBrace[k] = true;
                } else if (pt.is("=") && p >= 1 && f.sig.get(p - 1).isIdent() && f.enclosing[p - 1] >= 0 && f.initBrace[f.enclosing[p - 1]]) {
                    f.initBrace[k] = true;
                } else if (pt.isKeyword("new") || pt.isIdent("with")) {
                    f.initBrace[k] = true;
                }
            }
        }

        private static boolean isCastOperand(Token t) {
            return t.isIdent() || t.kind == Token.Kind.NUMBER || t.kind == Token.Kind.STRING || t.kind == Token.Kind.CHAR
                    || t.kind == Token.Kind.INTERP_START || t.isKeyword("this") || t.isKeyword("new") || t.is("!") || t.is("~")
                    || t.isKeyword("true") || t.isKeyword("false") || t.isKeyword("null") || t.isKeyword("typeof")
                    || t.isKeyword("default") || t.isKeyword("base") || t.isBuiltinType();
        }

        /** True when tokens [from, to] look like a (possibly qualified / generic / array) type name. */
        private static boolean isTypeChain(Model.CsFile f, int from, int to) {
            if (from > to) return false;
            boolean expectIdent = true;
            for (int k = from; k <= to; k++) {
                Token t = f.sig.get(k);
                if (expectIdent) {
                    if (!(t.isIdent() || t.isBuiltinType())) return false;
                    expectIdent = false;
                    if (t.isIdent() && Token.CONTEXTUAL_KEYWORDS.contains(t.value) && !t.value.equals("dynamic") && !t.value.equals("nint") && !t.value.equals("nuint")) return false;
                } else {
                    if (t.is(".") || t.is("::")) {
                        expectIdent = true;
                    } else if (t.is("<") && f.genericMatch[k] >= 0 && f.genericMatch[k] <= to) {
                        k = f.genericMatch[k];
                    } else if (t.is("[") && f.match[k] >= 0 && f.match[k] <= to) {
                        for (int j = k + 1; j < f.match[k]; j++) if (!f.sig.get(j).is(",")) return false;
                        k = f.match[k];
                    } else if (t.is("?") && k == to) {
                        // nullable
                    } else {
                        return false;
                    }
                }
            }
            return !expectIdent;
        }

        /** Index of the first token of the dotted/generic chain ending at idx. */
        static int chainStart(Model.CsFile f, int idx) {
            int j = idx;
            while (j >= 0) {
                Token t = f.sig.get(j);
                if (t.is("]") && f.match[j] >= 0) {
                    j = f.match[j] - 1;
                } else if (t.is(">") && f.genericMatch[j] >= 0) {
                    j = f.genericMatch[j] - 1;
                } else if (t.isIdent() || t.isBuiltinType()) {
                    if (j > 0 && (f.sig.get(j - 1).is(".") || f.sig.get(j - 1).is("::")) && j > 1 && f.sig.get(j - 2).isIdent()) {
                        j -= 2;
                        continue;
                    }
                    return j;
                } else {
                    return j + 1;
                }
            }
            return 0;
        }

        /** Mark the type chain starting at idx (IDENT ('.' IDENT)* with generics) as type context. */
        private static void markChain(Model.CsFile f, int idx) {
            int k = idx;
            int n = f.sig.size();
            while (k < n) {
                Token t = f.sig.get(k);
                if (t.isIdent()) {
                    f.typeCtx[k] = true;
                    k++;
                    if (k < n && f.sig.get(k).is("<") && f.genericMatch[k] >= 0) k = f.genericMatch[k] + 1;
                    if (k < n && (f.sig.get(k).is(".") || f.sig.get(k).is("::")) && k + 1 < n && f.sig.get(k + 1).isIdent()) {
                        k++;
                        continue;
                    }
                }
                break;
            }
        }

        /** If idx is directly inside a generic argument list, return the index of its '<'. */
        private static int enclosingGeneric(Model.CsFile f, int idx) {
            int depth = 0;
            for (int k = idx - 1; k >= 0; k--) {
                Token t = f.sig.get(k);
                if (t.is(">") && f.genericMatch[k] >= 0) {
                    k = f.genericMatch[k];
                    continue;
                }
                if (t.is("<") && f.genericMatch[k] >= 0) {
                    if (f.genericMatch[k] > idx) return k;
                    return -1;
                }
                if (t.isIdent() || t.is(".") || t.is(",") || t.is("::") || t.is("?") || t.is("[") || t.is("]") || t.isBuiltinType() || t.is("*")) continue;
                return -1;
            }
            return -1;
        }

        private static String closerFor(Token t) {
            if (t.kind == Token.Kind.INTERP_END) return "$";
            switch (t.text) {
                case ")": return "(";
                case "]": return "[";
                default: return "{";
            }
        }

        private static String openerText(Token t) {
            return t.kind == Token.Kind.INTERP_START ? "$" : t.text;
        }

        private static final Set<String> GENERIC_FOLLOW = Set.of(
                "(", ")", "]", "}", ":", ";", ",", ".", "?", "==", "!=", "|", "^", "&", "&&", "||", "[", "{", "=>", ">", "::");

        /** Try to interpret the '<' at idx as the start of a generic argument list. */
        private static void tryGeneric(Model.CsFile f, int lt) {
            int n = f.sig.size();
            List<int[]> pairs = new ArrayList<>();
            java.util.ArrayDeque<Integer> open = new java.util.ArrayDeque<>();
            open.push(lt);
            int k = lt + 1;
            while (k < n && !open.isEmpty()) {
                Token t = f.sig.get(k);
                if (t.is("<")) {
                    if (!(k > 0 && f.sig.get(k - 1).isIdent())) return;
                    open.push(k);
                } else if (t.is(">")) {
                    int o = open.pop();
                    pairs.add(new int[]{o, k});
                } else if (t.is("(") ) {
                    // tuple type inside generics
                    if (f.match[k] < 0) return;
                    for (int j = k + 1; j < f.match[k]; j++) {
                        Token u = f.sig.get(j);
                        if (!(u.isIdent() || u.isBuiltinType() || u.is(",") || u.is(".") || u.is("<") || u.is(">") || u.is("[") || u.is("]") || u.is("?"))) return;
                    }
                    k = f.match[k];
                } else if (t.is("[")) {
                    if (f.match[k] < 0) return;
                    for (int j = k + 1; j < f.match[k]; j++) if (!f.sig.get(j).is(",")) return;
                    k = f.match[k];
                } else if (!(t.isIdent() || t.isBuiltinType() || t.is(",") || t.is(".") || t.is("::") || t.is("?") || t.is("*"))) {
                    return;
                } else if (t.isIdent() && (t.value.equals("is") || t.value.equals("as"))) {
                    return;
                }
                k++;
            }
            if (!open.isEmpty()) return;
            int close = k - 1;
            Token follow = k < n ? f.sig.get(k) : null;
            boolean ok;
            if (follow == null) {
                ok = false;
            } else if (follow.kind == Token.Kind.INTERP_MID || follow.kind == Token.Kind.INTERP_END) {
                ok = true;
            } else if (GENERIC_FOLLOW.contains(follow.text) && follow.kind == Token.Kind.PUNCT) {
                ok = true;
            } else if (follow.isIdent() || follow.isKeyword("this") || follow.isKeyword("in") || follow.isKeyword("where")
                    || follow.isKeyword("operator")) {
                ok = follow.isIdent("where") || isDeclarationContext(f, lt - 1);
            } else {
                ok = false;
            }
            if (!ok) return;
            for (int[] p : pairs) {
                f.genericMatch[p[0]] = p[1];
                f.genericMatch[p[1]] = p[0];
            }
        }

        /** Whether the type chain ending at nameIdx starts in a position where a declaration may appear. */
        private static boolean isDeclarationContext(Model.CsFile f, int nameIdx) {
            int start = chainStart(f, nameIdx);
            if (start == 0) return true;
            Token prev = f.sig.get(start - 1);
            if (prev.is(";") || prev.is("{") || prev.is("}") || prev.is("]") || prev.is(":")) return true;
            if (prev.kind == Token.Kind.KEYWORD) {
                // modifiers, 'out', 'ref', 'params', 'const', 'using', 'foreach', etc.
                return !prev.text.equals("return") && !prev.text.equals("throw") && !prev.text.equals("is")
                        && !prev.text.equals("as") && !prev.text.equals("new") && !prev.text.equals("case")
                        && !prev.text.equals("else") && !prev.text.equals("in") && !prev.text.equals("do");
            }
            if (prev.isIdent()) {
                return prev.isIdent("partial") || prev.isIdent("async") || prev.isIdent("required") || prev.isIdent("scoped");
            }
            if (prev.is("(") || prev.is(",")) {
                int open = prev.is("(") ? start - 1 : f.enclosing[start - 1];
                while (open >= 0 && !f.sig.get(open).is("(")) {
                    if (f.sig.get(open).is("{") || f.sig.get(open).is("[")) return true;
                    open = f.enclosing[open];
                }
                if (open <= 0) return true;
                Token b = f.sig.get(open - 1);
                // invocation: name( , )( , ]( , >(  => expression context
                if (b.isIdent() || b.is(")") || b.is("]") || b.is(">") || b.isKeyword("this") || b.isKeyword("base")) {
                    return false;
                }
                return true;
            }
            return false;
        }
    }
}
