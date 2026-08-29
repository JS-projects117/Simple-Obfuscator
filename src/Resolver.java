import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Lightweight name/type resolver. It binds identifier tokens to project
 * declarations (types, members, locals) or reports that they refer to something
 * outside the project (or cannot be determined). It also infers the static type
 * of receiver expressions well enough to follow member accesses through project
 * types, generics with explicit type arguments, arrays and common collections.
 *
 * Anything it cannot resolve is reported as UNKNOWN/EXTERNAL; the analyzer
 * treats both as "do not rename".
 */
public final class Resolver {

    /** Result of binding one identifier token. */
    public static final class Binding {
        public enum Kind { LOCAL, MEMBER, TYPE, NAMESPACE, TYPE_PARAM, EXTERNAL, UNKNOWN }

        public final Kind kind;
        public final Model.LocalVar local;
        public final Model.MemberDecl member;
        public final Model.TypeRef type;   // for TYPE: the type; for MEMBER: the member's type; for LOCAL: the local's type

        Binding(Kind kind, Model.LocalVar local, Model.MemberDecl member, Model.TypeRef type) {
            this.kind = kind;
            this.local = local;
            this.member = member;
            this.type = type;
        }

        static final Binding EXTERNAL = new Binding(Kind.EXTERNAL, null, null, Model.TypeRef.UNKNOWN);
        static final Binding UNKNOWN = new Binding(Kind.UNKNOWN, null, null, Model.TypeRef.UNKNOWN);

        public boolean isProject() {
            return kind == Kind.LOCAL || kind == Kind.MEMBER || kind == Kind.TYPE || kind == Kind.NAMESPACE || kind == Kind.TYPE_PARAM;
        }

        @Override
        public String toString() {
            return kind + (member != null ? " " + member : "") + (local != null ? " local " + local.name : "") + " : " + type;
        }
    }

    /** Resolution context: where in the code we are. */
    public static final class Ctx {
        final Model.CsFile file;
        final Model.TypeDecl type;
        final Model.MemberDecl member;
        final Map<String, Model.TypeRef> bindings;

        Ctx(Model.CsFile file, Model.TypeDecl type, Model.MemberDecl member, Map<String, Model.TypeRef> bindings) {
            this.file = file;
            this.type = type;
            this.member = member;
            this.bindings = bindings == null ? Map.of() : bindings;
        }
    }

    /** Result of looking a member up in a type (walking base types). */
    static final class MemberHit {
        final Model.MemberDecl member;     // null when nested type
        final Model.TypeDecl nestedType;   // non-null for nested type hits
        final Model.TypeRef ownerRef;      // the (generic-instantiated) type that owns the member
        final boolean ambiguousReturn;

        MemberHit(Model.MemberDecl member, Model.TypeDecl nestedType, Model.TypeRef ownerRef, boolean ambiguousReturn) {
            this.member = member;
            this.nestedType = nestedType;
            this.ownerRef = ownerRef;
            this.ambiguousReturn = ambiguousReturn;
        }
    }

    /** Generic helpers on Unity's Object/Component that return their type argument (or an array of it). */
    private static final Set<String> UNITY_GENERIC_T = Set.of(
            "GetComponent", "GetComponentInChildren", "GetComponentInParent", "AddComponent", "FindObjectOfType",
            "FindFirstObjectByType", "FindAnyObjectByType", "GetComponentInChildrenOrParent", "Load", "Instantiate",
            "TryGetComponent", "GetOrAddComponent", "FindObjectOfTypeAll");
    private static final Set<String> UNITY_GENERIC_ARRAY = Set.of(
            "GetComponents", "GetComponentsInChildren", "GetComponentsInParent", "FindObjectsOfType",
            "FindObjectsByType", "LoadAll", "FindObjectsOfTypeAll");
    private static final Set<String> ELEMENT_COLLECTIONS = Set.of(
            "List", "IList", "IReadOnlyList", "IEnumerable", "ICollection", "IReadOnlyCollection", "HashSet", "ISet",
            "Stack", "Queue", "LinkedList", "SortedSet", "ReadOnlyCollection", "Span", "ReadOnlySpan", "Memory",
            "ObservableCollection", "IReadOnlySet", "ConcurrentBag", "ConcurrentQueue", "ConcurrentStack", "ArraySegment",
            "NativeArray", "NativeList", "IQueryable");
    private static final Set<String> VALUE_COLLECTIONS = Set.of(
            "Dictionary", "IDictionary", "IReadOnlyDictionary", "SortedDictionary", "SortedList", "ConcurrentDictionary");

    private final Model.Project project;
    private final Set<String> constraintInProgress = new HashSet<>();
    private final Set<String> simpleInProgress = new HashSet<>();

    public Resolver(Model.Project project) {
        this.project = project;
    }

    // ------------------------------------------------------------------ contexts

    public Ctx ctxAt(Model.CsFile f, int idx) {
        return new Ctx(f, f.typeOf[idx], f.memberOf[idx], null);
    }

    private Ctx ctxOf(Model.MemberDecl m, Map<String, Model.TypeRef> bindings) {
        return new Ctx(m.file, m.owner, m, bindings);
    }

    private Ctx ctxOf(Model.TypeDecl t, Map<String, Model.TypeRef> bindings) {
        Model.CsFile file = t.sites.isEmpty() ? null : t.sites.get(0).file;
        return new Ctx(file, t, null, bindings);
    }

    private static Map<String, Model.TypeRef> bind(List<String> params, List<Model.TypeRef> args) {
        if (params.isEmpty() || args.isEmpty()) return Map.of();
        Map<String, Model.TypeRef> m = new HashMap<>();
        for (int i = 0; i < params.size() && i < args.size(); i++) m.put(params.get(i), args.get(i));
        return m;
    }

    /** this-reference for a type: its own type parameters stay as type parameters. */
    private Model.TypeRef selfRef(Model.TypeDecl t) {
        List<Model.TypeRef> args = new ArrayList<>();
        for (String tp : t.typeParams) args.add(Model.TypeRef.typeParam(tp, constraintOf(t.constraints, tp, ctxOf(t, null))));
        return Model.TypeRef.project(t, args);
    }

    private Model.TypeRef constraintOf(Map<String, List<Model.TypeRange>> constraints, String tp, Ctx ctx) {
        List<Model.TypeRange> list = constraints.get(tp);
        if (list == null || list.isEmpty()) return null;
        String key = System.identityHashCode(constraints) + ":" + tp;
        if (!constraintInProgress.add(key)) return null;
        try {
            for (Model.TypeRange tr : list) {
                Model.TypeRef r = resolveTypeRange(tr, ctx);
                if (r.kind == Model.TypeRef.Kind.PROJECT) return r;
            }
            return null;
        } finally {
            constraintInProgress.remove(key);
        }
    }

    // ------------------------------------------------------------------ type resolution

    public Model.TypeRef resolveTypeRange(Model.TypeRange tr, Ctx ctx) {
        if (tr == null) return Model.TypeRef.UNKNOWN;
        return resolveTypeTokens(tr.file, tr.start, tr.end, ctx);
    }

    /** Resolve a type spelled by tokens [start, end] of file f. */
    public Model.TypeRef resolveTypeTokens(Model.CsFile f, int start, int end, Ctx ctx) {
        int[] pos = {start};
        Model.TypeRef r = parseType(f, pos, end, ctx);
        return r == null ? Model.TypeRef.UNKNOWN : r;
    }

    private Model.TypeRef parseType(Model.CsFile f, int[] pos, int end, Ctx ctx) {
        int j = pos[0];
        while (j <= end && (f.tok(j).isKeyword("ref") || f.tok(j).isKeyword("readonly") || f.tok(j).isKeyword("in")
                || f.tok(j).isKeyword("out") || f.tok(j).isKeyword("params") || f.tok(j).isIdent("scoped") || f.tok(j).isKeyword("this"))) {
            j++;
        }
        if (j > end) return null;
        Token t = f.tok(j);
        Model.TypeRef cur;
        if (t.is("(")) {
            int c = f.match[j];
            if (c < 0 || c > end) return null;
            cur = Model.TypeRef.external("ValueTuple", List.of());
            j = c + 1;
        } else if (t.isBuiltinType()) {
            cur = Model.TypeRef.external(t.text, List.of());
            j++;
        } else if (t.isIdent()) {
            cur = null;
            if (t.isIdent("global") && j + 1 <= end && f.tok(j + 1).is("::")) {
                cur = Model.TypeRef.namespace("");
                j += 2;
            }
            while (true) {
                if (j > end || !f.tok(j).isIdent()) return null;
                String name = f.tok(j).value;
                j++;
                List<Model.TypeRef> args = new ArrayList<>();
                if (j <= end && f.tok(j).is("<") && f.genericMatch[j] >= 0 && f.genericMatch[j] <= end) {
                    int close = f.genericMatch[j];
                    int k = j + 1;
                    int argStart = k;
                    int depth = 0;
                    for (; k <= close; k++) {
                        Token u = f.tok(k);
                        if (u.is("<") && f.genericMatch[k] >= 0) {
                            k = f.genericMatch[k];
                            continue;
                        }
                        if (u.is("(") || u.is("[")) depth++;
                        if (u.is(")") || u.is("]")) depth--;
                        if ((u.is(",") && depth == 0) || k == close) {
                            if (k - 1 >= argStart) {
                                args.add(resolveTypeTokens(f, argStart, k - 1, ctx));
                            } else {
                                args.add(Model.TypeRef.UNKNOWN);
                            }
                            argStart = k + 1;
                        }
                    }
                    j = close + 1;
                }
                cur = cur == null ? resolveSimpleType(name, args.size(), args, ctx) : resolveQualified(cur, name, args, ctx);
                if (j + 1 <= end && (f.tok(j).is(".") || f.tok(j).is("::")) && f.tok(j + 1).isIdent()) {
                    j++;
                    continue;
                }
                break;
            }
        } else {
            return null;
        }
        int rank = 0;
        while (j <= end) {
            Token u = f.tok(j);
            if (u.is("?") || u.is("*")) {
                j++;
            } else if (u.is("[") && f.match[j] >= 0 && f.match[j] <= end) {
                rank++;
                j = f.match[j] + 1;
            } else {
                break;
            }
        }
        pos[0] = j;
        return rank > 0 ? cur.withArrayRank(rank) : cur;
    }

    /** Resolve a member of a namespace or a nested type of a type. */
    public Model.TypeRef resolveQualified(Model.TypeRef cur, String name, List<Model.TypeRef> args, Ctx ctx) {
        switch (cur.kind) {
            case NAMESPACE: {
                String full = cur.name.isEmpty() ? name : cur.name + "." + name;
                Model.TypeDecl d = project.findType(full, args.size());
                if (d == null && args.isEmpty()) d = project.findTypeAnyArity(full, 0);
                if (d != null) return Model.TypeRef.project(d, args);
                if (project.namespaces.contains(full)) return Model.TypeRef.namespace(full);
                return Model.TypeRef.external(name, args);
            }
            case PROJECT: {
                Model.TypeDecl nested = findNested(cur.decl, name, args.size(), new HashSet<>());
                if (nested != null) return Model.TypeRef.project(nested, args);
                return Model.TypeRef.external(name, args);
            }
            default:
                return Model.TypeRef.external(name, args);
        }
    }

    private Model.TypeDecl findNested(Model.TypeDecl t, String name, int arity, Set<Model.TypeDecl> visited) {
        if (t == null || !visited.add(t)) return null;
        for (Model.TypeDecl n : t.nested) {
            if (n.name.equals(name) && n.typeParams.size() == arity) return n;
        }
        for (Model.TypeDecl n : t.nested) {
            if (n.name.equals(name)) return n;
        }
        // nested types are inherited
        for (Model.TypeRange b : t.bases) {
            Model.TypeRef br = resolveTypeRange(b, ctxOf(t, null));
            if (br.kind == Model.TypeRef.Kind.PROJECT) {
                Model.TypeDecl r = findNested(br.decl, name, arity, visited);
                if (r != null) return r;
            }
        }
        return null;
    }

    /** Resolve an unqualified type name in a context. */
    public Model.TypeRef resolveSimpleType(String name, int arity, List<Model.TypeRef> args, Ctx ctx) {
        Model.TypeRef bound = ctx.bindings.get(name);
        if (bound != null) return bound;
        if (ctx.member != null && ctx.member.typeParams.contains(name)) {
            return Model.TypeRef.typeParam(name, constraintOf(ctx.member.constraints, name, ctx));
        }
        // enclosing types: type parameters and nested types.
        // The nested-type search walks base types, which can recurse back here while resolving a
        // base's own name; guard against that so we fall through to namespace / using resolution.
        String guardKey = System.identityHashCode(ctx.type) + "#" + name + "#" + arity;
        boolean guardOwner = simpleInProgress.add(guardKey);
        try {
            for (Model.TypeDecl t = ctx.type; t != null; t = t.outer) {
                if (t.typeParams.contains(name)) {
                    return Model.TypeRef.typeParam(name, constraintOf(t.constraints, name, ctxOf(t, null)));
                }
                if (guardOwner) {
                    Model.TypeDecl nested = findNested(t, name, arity, new HashSet<>());
                    if (nested != null) return Model.TypeRef.project(nested, args);
                }
            }
        } finally {
            if (guardOwner) simpleInProgress.remove(guardKey);
        }
        // the enclosing type itself (by simple name) and its outers
        for (Model.TypeDecl t = ctx.type; t != null; t = t.outer) {
            if (t.name.equals(name) && t.typeParams.size() == arity) return Model.TypeRef.project(t, args);
        }
        // aliases
        if (ctx.file != null) {
            int[] alias = ctx.file.aliases.get(name);
            if (alias != null) {
                return resolveTypeTokens(ctx.file, alias[0], alias[1], new Ctx(ctx.file, null, null, null));
            }
        }
        // namespace chain
        String ns = ctx.type != null ? ctx.type.rootNamespace() : "";
        while (true) {
            Model.TypeDecl d = project.findType(ns.isEmpty() ? name : ns + "." + name, arity);
            if (d != null) return Model.TypeRef.project(d, args);
            int dot = ns.lastIndexOf('.');
            if (ns.isEmpty()) break;
            ns = dot < 0 ? "" : ns.substring(0, dot);
        }
        // usings
        if (ctx.file != null) {
            for (String u : ctx.file.usings) {
                Model.TypeDecl d = project.findType(u + "." + name, arity);
                if (d != null) return Model.TypeRef.project(d, args);
            }
        }
        // any-arity fallback along the same search path
        ns = ctx.type != null ? ctx.type.rootNamespace() : "";
        while (true) {
            Model.TypeDecl d = project.findTypeAnyArity(ns.isEmpty() ? name : ns + "." + name, arity);
            if (d != null) return Model.TypeRef.project(d, args);
            int dot = ns.lastIndexOf('.');
            if (ns.isEmpty()) break;
            ns = dot < 0 ? "" : ns.substring(0, dot);
        }
        if (ctx.file != null) {
            for (String u : ctx.file.usings) {
                Model.TypeDecl d = project.findTypeAnyArity(u + "." + name, arity);
                if (d != null) return Model.TypeRef.project(d, args);
            }
        }
        if (project.namespaces.contains(name)) return Model.TypeRef.namespace(name);
        return Model.TypeRef.external(name, args);
    }

    // ------------------------------------------------------------------ member lookup

    /**
     * Find a member (or nested type) by name in a type and its project base types.
     * Returns null if not found in the project part of the hierarchy; the returned hit
     * has member == null && nestedType == null when the lookup fell into an external base.
     */
    public MemberHit findMember(Model.TypeRef t, String name, int argCount, List<Model.TypeRef> typeArgs) {
        return findMember(t, name, argCount, typeArgs, new HashSet<>());
    }

    private static final MemberHit EXTERNAL_HIT = new MemberHit(null, null, null, false);

    private MemberHit findMember(Model.TypeRef t, String name, int argCount, List<Model.TypeRef> typeArgs, Set<Model.TypeDecl> visited) {
        Model.TypeRef src = t.memberSource();
        if (src.kind != Model.TypeRef.Kind.PROJECT) {
            return src.kind == Model.TypeRef.Kind.EXTERNAL ? EXTERNAL_HIT : null;
        }
        Model.TypeDecl decl = src.decl;
        if (!visited.add(decl)) return null;

        List<Model.MemberDecl> cands = new ArrayList<>();
        for (Model.MemberDecl m : decl.members) {
            if (m.name.equals(name) && m.kind != Model.MemberKind.CTOR && m.kind != Model.MemberKind.OPERATOR && m.kind != Model.MemberKind.DTOR
                    && m.kind != Model.MemberKind.INDEXER) {
                cands.add(m);
            }
        }
        if (!cands.isEmpty()) {
            Model.MemberDecl chosen = cands.get(0);
            boolean ambiguous = false;
            if (argCount >= 0) {
                List<Model.MemberDecl> byCount = new ArrayList<>();
                for (Model.MemberDecl m : cands) {
                    if (m.kind == Model.MemberKind.METHOD && (m.params.size() == argCount || (m.hasParamsArray && argCount >= m.params.size() - 1))) {
                        if (typeArgs.isEmpty() || m.typeParams.size() == typeArgs.size()) byCount.add(m);
                    }
                }
                if (!byCount.isEmpty()) {
                    chosen = byCount.get(0);
                    for (Model.MemberDecl m : byCount) {
                        if (!sameTypeText(m.type, chosen.type)) ambiguous = true;
                    }
                } else {
                    for (Model.MemberDecl m : cands) {
                        if (!sameTypeText(m.type, chosen.type)) ambiguous = true;
                    }
                }
            } else {
                for (Model.MemberDecl m : cands) {
                    if (!sameTypeText(m.type, chosen.type)) ambiguous = true;
                }
            }
            return new MemberHit(chosen, null, src, ambiguous);
        }
        for (Model.TypeDecl n : decl.nested) {
            if (n.name.equals(name)) return new MemberHit(null, n, src, false);
        }
        boolean sawExternal = false;
        Map<String, Model.TypeRef> bindings = bind(decl.typeParams, src.args);
        for (Model.TypeRange b : decl.bases) {
            Model.TypeRef br = resolveTypeRange(b, ctxOf(decl, bindings));
            if (br.kind == Model.TypeRef.Kind.PROJECT) {
                MemberHit h = findMember(br, name, argCount, typeArgs, visited);
                if (h != null && h != EXTERNAL_HIT) return h;
                if (h == EXTERNAL_HIT) sawExternal = true;
            } else if (br.kind == Model.TypeRef.Kind.EXTERNAL || br.kind == Model.TypeRef.Kind.UNKNOWN) {
                sawExternal = true;
            } else if (br.kind == Model.TypeRef.Kind.TYPE_PARAM && br.constraint != null) {
                MemberHit h = findMember(br.constraint, name, argCount, typeArgs, visited);
                if (h != null && h != EXTERNAL_HIT) return h;
            }
        }
        return sawExternal ? EXTERNAL_HIT : null;
    }

    private static boolean sameTypeText(Model.TypeRange a, Model.TypeRange b) {
        if (a == null || b == null) return a == b;
        return a.text().equals(b.text());
    }

    /** The type of a member hit (field type, property type, method return type, enum type, nested type). */
    public Model.TypeRef memberType(MemberHit hit, List<Model.TypeRef> typeArgs) {
        if (hit == null || hit == EXTERNAL_HIT) return Model.TypeRef.UNKNOWN;
        if (hit.nestedType != null) return Model.TypeRef.project(hit.nestedType, typeArgs);
        Model.MemberDecl m = hit.member;
        if (m.kind == Model.MemberKind.ENUM_MEMBER) return Model.TypeRef.project(m.owner, List.of());
        if (m.type == null || hit.ambiguousReturn) return Model.TypeRef.UNKNOWN;
        Map<String, Model.TypeRef> bindings = new HashMap<>(bind(m.owner.typeParams, hit.ownerRef.args));
        if (!typeArgs.isEmpty()) bindings.putAll(bind(m.typeParams, typeArgs));
        Model.TypeRef r = resolveTypeRange(m.type, ctxOf(m, bindings));
        if (m.owner.kind.equals("delegate")) return Model.TypeRef.UNKNOWN;
        return r;
    }

    // ------------------------------------------------------------------ locals

    public Model.LocalVar lookupLocal(Model.MemberDecl m, String name, int idx) {
        if (m == null) return null;
        Model.LocalVar best = null;
        for (Model.LocalVar lv : m.locals) {
            if (!lv.name.equals(name)) continue;
            if (idx < lv.scopeStart || idx > lv.scopeEnd) continue;
            if (best == null || lv.declTok > best.declTok) best = lv;
        }
        return best;
    }

    /** Infer the types of all locals of a member, in declaration order. */
    public void inferLocals(Model.MemberDecl m) {
        for (Model.LocalVar lv : m.locals) {
            if (lv.type != null) continue;
            if (lv.typeRange != null && !lv.isLocalFunction) {
                lv.type = resolveTypeRange(lv.typeRange, ctxOf(m, null));
            } else if (lv.isLocalFunction) {
                lv.type = Model.TypeRef.UNKNOWN;
            } else if (lv.initRange != null) {
                Model.TypeRef t = exprRange(m.file, lv.initRange[0], lv.initRange[1]);
                lv.type = lv.isForeach ? elementOf(t) : t;
            } else {
                lv.type = Model.TypeRef.UNKNOWN;
            }
        }
    }

    private Model.TypeRef elementOf(Model.TypeRef t) {
        if (t.isArray()) return t.elementType();
        if (t.kind == Model.TypeRef.Kind.EXTERNAL && !t.args.isEmpty()) {
            if (ELEMENT_COLLECTIONS.contains(t.name)) return t.args.get(0);
            if (VALUE_COLLECTIONS.contains(t.name)) return Model.TypeRef.external("KeyValuePair", t.args);
        }
        if (t.kind == Model.TypeRef.Kind.PROJECT) {
            // project type implementing IEnumerable<X>
            for (Model.TypeRange b : t.decl.bases) {
                Model.TypeRef br = resolveTypeRange(b, ctxOf(t.decl, bind(t.decl.typeParams, t.args)));
                if (br.kind == Model.TypeRef.Kind.EXTERNAL && (br.name.equals("IEnumerable") || br.name.equals("IList") || br.name.equals("List")
                        || br.name.equals("ICollection") || br.name.equals("IReadOnlyList")) && !br.args.isEmpty()) {
                    return br.args.get(0);
                }
            }
        }
        return Model.TypeRef.UNKNOWN;
    }

    // ------------------------------------------------------------------ expressions

    /** Type of the expression spanning tokens [start, end]. */
    public Model.TypeRef exprRange(Model.CsFile f, int start, int end) {
        if (start > end || start < 0 || end >= f.sig.size()) return Model.TypeRef.UNKNOWN;
        Token first = f.tok(start);
        // strip prefix unary operators
        while (start < end && (first.is("!") || first.is("-") || first.is("+") || first.is("~") || first.is("++") || first.is("--")
                || first.isIdent("await") || first.isKeyword("ref") || first.isKeyword("out"))) {
            start++;
            first = f.tok(start);
        }
        Ctx ctx = ctxAt(f, start);
        if (first.isKeyword("new")) {
            int k = start + 1;
            if (k > end) return Model.TypeRef.UNKNOWN;
            if (f.tok(k).is("(") || f.tok(k).is("[") || f.tok(k).is("{")) return Model.TypeRef.UNKNOWN; // new(), new[] {..}, new { }
            int typeEnd = k;
            int j = k;
            while (j <= end) {
                Token u = f.tok(j);
                if (u.isIdent() || u.isBuiltinType() || u.is(".") || u.is("::") || u.is("?")) {
                    typeEnd = j;
                    j++;
                } else if (u.is("<") && f.genericMatch[j] >= 0) {
                    typeEnd = f.genericMatch[j];
                    j = typeEnd + 1;
                } else if (u.is("[") && f.match[j] >= 0) {
                    // array creation: new T[n] or new T[] { }
                    Model.TypeRef base = resolveTypeTokens(f, k, typeEnd, ctx);
                    int rank = 0;
                    while (j <= end && f.tok(j).is("[") && f.match[j] >= 0) {
                        rank++;
                        j = f.match[j] + 1;
                    }
                    return base.withArrayRank(base.arrayRank + rank);
                } else {
                    break;
                }
            }
            return resolveTypeTokens(f, k, typeEnd, ctx);
        }
        if (first.is("(") && f.match[start] >= 0 && f.match[start] < end) {
            int close = f.match[start];
            if (Parser.Prepass.chainStart(f, close - 1) == start + 1 && looksLikeType(f, start + 1, close - 1)) {
                // cast
                return resolveTypeTokens(f, start + 1, close - 1, ctx);
            }
        }
        if (first.is("(") && f.match[start] == end) {
            return exprRange(f, start + 1, end - 1);
        }
        // binary operators at depth 0 => compound expression
        int depth = 0;
        for (int k = start; k <= end; k++) {
            Token u = f.tok(k);
            if (u.isOpener()) {
                if (f.match[k] > 0) {
                    k = f.match[k];
                    continue;
                }
                depth++;
            } else if (u.isCloser()) {
                depth--;
            } else if (depth == 0 && k > start) {
                if (u.is("<") && f.genericMatch[k] >= 0) {
                    k = f.genericMatch[k];
                    continue;
                }
                if (u.isKeyword("as") && k + 1 <= end) return resolveTypeTokens(f, k + 1, end, ctx);
                if (u.isKeyword("is")) return Model.TypeRef.external("bool", List.of());
                if (u.kind == Token.Kind.PUNCT && !u.is(".") && !u.is("?.") && !u.is("::") && !u.is("!")) {
                    return Model.TypeRef.UNKNOWN;
                }
                if (u.kind == Token.Kind.KEYWORD && !u.isKeyword("this") && !u.isKeyword("base")) return Model.TypeRef.UNKNOWN;
            }
        }
        return exprEndingAt(f, end);
    }

    private boolean looksLikeType(Model.CsFile f, int from, int to) {
        for (int k = from; k <= to; k++) {
            Token u = f.tok(k);
            if (!(u.isIdent() || u.isBuiltinType() || u.is(".") || u.is("::") || u.is("?") || u.is("[") || u.is("]") || u.is(",") || u.is("<") || u.is(">"))) return false;
        }
        return f.tok(from).isIdent() || f.tok(from).isBuiltinType();
    }

    /** Type of the primary expression whose last token is idx. */
    public Model.TypeRef exprEndingAt(Model.CsFile f, int idx) {
        if (idx < 0 || idx >= f.sig.size()) return Model.TypeRef.UNKNOWN;
        Token t = f.tok(idx);
        Ctx ctx = ctxAt(f, idx);
        switch (t.kind) {
            case IDENT: {
                Binding b = bind(f, idx);
                return b.type == null ? Model.TypeRef.UNKNOWN : b.type;
            }
            case KEYWORD:
                if (t.isKeyword("this")) return ctx.type != null ? selfRef(ctx.type) : Model.TypeRef.UNKNOWN;
                if (t.isKeyword("base")) return baseClassOf(ctx.type);
                if (t.isKeyword("true") || t.isKeyword("false")) return Model.TypeRef.external("bool", List.of());
                if (t.isBuiltinType()) return Model.TypeRef.external(t.text, List.of());
                return Model.TypeRef.UNKNOWN;
            case STRING:
            case INTERP_END:
                return Model.TypeRef.external("string", List.of());
            case CHAR:
                return Model.TypeRef.external("char", List.of());
            case NUMBER:
                return Model.TypeRef.external("number", List.of());
            case PUNCT:
                break;
            default:
                return Model.TypeRef.UNKNOWN;
        }
        if (t.is(")")) {
            int open = f.match[idx];
            if (open < 0) return Model.TypeRef.UNKNOWN;
            int k = open - 1;
            if (k >= 0 && (f.tok(k).isIdent() || (f.tok(k).is(">") && f.genericMatch[k] >= 0))) {
                return callType(f, open, idx, ctx);
            }
            if (k >= 0 && f.tok(k).isKeyword("base") || k >= 0 && f.tok(k).isKeyword("this")) return Model.TypeRef.UNKNOWN;
            if (k >= 0 && f.tok(k).kind == Token.Kind.KEYWORD) {
                if (f.tok(k).isKeyword("typeof")) return Model.TypeRef.external("Type", List.of());
                if (f.tok(k).isKeyword("nameof")) return Model.TypeRef.external("string", List.of());
                if (f.tok(k).isKeyword("sizeof")) return Model.TypeRef.external("int", List.of());
                if (f.tok(k).isKeyword("default")) return resolveTypeTokens(f, open + 1, idx - 1, ctx);
                if (f.tok(k).isKeyword("checked") || f.tok(k).isKeyword("unchecked")) return exprRange(f, open + 1, idx - 1);
            }
            // parenthesized expression (possibly a cast)
            return exprRange(f, open + 1, idx - 1);
        }
        if (t.is("]")) {
            int open = f.match[idx];
            if (open <= 0) return Model.TypeRef.UNKNOWN;
            if (f.tok(open - 1).isKeyword("new")) return Model.TypeRef.UNKNOWN;
            Model.TypeRef recv = exprEndingAt(f, open - 1);
            return indexedType(recv);
        }
        if (t.is(">") && f.genericMatch[idx] >= 0) {
            int lt = f.genericMatch[idx];
            int cs = Parser.Prepass.chainStart(f, lt - 1);
            return resolveTypeTokens(f, cs, idx, ctx);
        }
        if (t.is("}")) {
            int open = f.match[idx];
            if (open <= 0 || !f.initBrace[open]) return Model.TypeRef.UNKNOWN;
            return initializerTarget(f, open);
        }
        if (t.is("!") && idx > 0) return exprEndingAt(f, idx - 1); // null-forgiving
        return Model.TypeRef.UNKNOWN;
    }

    private Model.TypeRef indexedType(Model.TypeRef recv) {
        if (recv.isArray()) return recv.elementType();
        if (recv.kind == Model.TypeRef.Kind.EXTERNAL && !recv.args.isEmpty()) {
            if (ELEMENT_COLLECTIONS.contains(recv.name)) return recv.args.get(0);
            if (VALUE_COLLECTIONS.contains(recv.name)) return recv.args.get(recv.args.size() - 1);
        }
        if (recv.kind == Model.TypeRef.Kind.PROJECT) {
            for (Model.MemberDecl m : recv.decl.members) {
                if (m.kind == Model.MemberKind.INDEXER && m.type != null) {
                    return resolveTypeRange(m.type, ctxOf(m, bind(recv.decl.typeParams, recv.args)));
                }
            }
        }
        return Model.TypeRef.UNKNOWN;
    }

    /** Type of the object whose initializer starts at the '{' open. */
    public Model.TypeRef initializerTarget(Model.CsFile f, int open) {
        int p = open - 1;
        if (p < 0) return Model.TypeRef.UNKNOWN;
        Token pt = f.tok(p);
        if (pt.is("=") && p >= 1 && f.tok(p - 1).isIdent()) {
            Binding b = bind(f, p - 1);
            return b.type == null ? Model.TypeRef.UNKNOWN : b.type;
        }
        if (pt.isKeyword("new") || pt.isIdent("with")) return Model.TypeRef.UNKNOWN;
        if (pt.is(")") && f.match[p] >= 0) {
            return exprEndingAt(f, p);
        }
        Ctx ctx = ctxAt(f, open);
        int cs = Parser.Prepass.chainStart(f, p);
        if (cs > 0 && f.tok(cs - 1).isKeyword("new")) {
            // "new T { }" or "new T[] { }"
            int typeEnd = p;
            int rank = 0;
            while (typeEnd > cs && f.tok(typeEnd).is("]") && f.match[typeEnd] >= 0) {
                rank++;
                typeEnd = f.match[typeEnd] - 1;
            }
            Model.TypeRef base = resolveTypeTokens(f, cs, typeEnd, ctx);
            return rank > 0 ? base.withArrayRank(rank) : base;
        }
        return Model.TypeRef.UNKNOWN;
    }

    private Model.TypeRef baseClassOf(Model.TypeDecl t) {
        if (t == null) return Model.TypeRef.UNKNOWN;
        for (Model.TypeRange b : t.bases) {
            Model.TypeRef br = resolveTypeRange(b, ctxOf(t, null));
            if (br.kind == Model.TypeRef.Kind.PROJECT && !br.decl.isInterface()) return br;
            if (br.kind == Model.TypeRef.Kind.EXTERNAL) return br;
        }
        return Model.TypeRef.UNKNOWN;
    }

    private static int countArgs(Model.CsFile f, int open, int close) {
        if (close == open + 1) return 0;
        int n = 1;
        for (int k = open + 1; k < close; k++) {
            Token u = f.tok(k);
            if (u.isOpener() && f.match[k] > 0) {
                k = f.match[k];
                continue;
            }
            if (u.is(",")) n++;
        }
        return n;
    }

    private List<Model.TypeRef> typeArgsBefore(Model.CsFile f, int nameEnd, Ctx ctx, int[] nameIdxOut) {
        List<Model.TypeRef> typeArgs = new ArrayList<>();
        int nameIdx = nameEnd;
        if (f.tok(nameEnd).is(">") && f.genericMatch[nameEnd] >= 0) {
            int lt = f.genericMatch[nameEnd];
            nameIdx = lt - 1;
            int argStart = lt + 1;
            int depth = 0;
            for (int k = lt + 1; k <= nameEnd; k++) {
                Token u = f.tok(k);
                if (u.is("<") && f.genericMatch[k] >= 0) {
                    k = f.genericMatch[k];
                    continue;
                }
                if (u.is("(") || u.is("[")) depth++;
                if (u.is(")") || u.is("]")) depth--;
                if ((u.is(",") && depth == 0) || k == nameEnd) {
                    typeArgs.add(k - 1 >= argStart ? resolveTypeTokens(f, argStart, k - 1, ctx) : Model.TypeRef.UNKNOWN);
                    argStart = k + 1;
                }
            }
        }
        nameIdxOut[0] = nameIdx;
        return typeArgs;
    }

    /** Type of an invocation "name(...)" / "recv.name<T>(...)" / "new T(...)" whose parens are [open, close]. */
    private Model.TypeRef callType(Model.CsFile f, int open, int close, Ctx ctx) {
        int[] nameIdxOut = new int[1];
        List<Model.TypeRef> typeArgs = typeArgsBefore(f, open - 1, ctx, nameIdxOut);
        int nameIdx = nameIdxOut[0];
        if (nameIdx < 0 || !f.tok(nameIdx).isIdent()) return Model.TypeRef.UNKNOWN;
        String name = f.tok(nameIdx).value;
        int argCount = countArgs(f, open, close);
        int cs = Parser.Prepass.chainStart(f, nameIdx);
        if (cs > 0 && f.tok(cs - 1).isKeyword("new")) {
            return resolveTypeTokens(f, cs, open - 1, ctx);
        }
        Binding b = bindCall(f, nameIdx, argCount, typeArgs);
        if (b.kind == Binding.Kind.MEMBER || b.kind == Binding.Kind.LOCAL) return b.type;
        // Unity generic helpers regardless of receiver
        if (typeArgs.size() == 1) {
            if (UNITY_GENERIC_T.contains(name)) return typeArgs.get(0);
            if (UNITY_GENERIC_ARRAY.contains(name)) return typeArgs.get(0).withArrayRank(1);
        }
        if (name.equals("Instantiate") && typeArgs.isEmpty() && argCount >= 1) {
            int firstEnd = open + 1;
            int depth = 0;
            for (int k = open + 1; k < close; k++) {
                Token u = f.tok(k);
                if (u.isOpener() && f.match[k] > 0) {
                    k = f.match[k];
                    firstEnd = k;
                    continue;
                }
                if (u.is(",") && depth == 0) break;
                firstEnd = k;
            }
            return exprRange(f, open + 1, firstEnd);
        }
        return Model.TypeRef.UNKNOWN;
    }

    // ------------------------------------------------------------------ identifier binding

    /** Bind the identifier token at idx (not a call). */
    public Binding bind(Model.CsFile f, int idx) {
        int argCount = -1;
        List<Model.TypeRef> typeArgs = List.of();
        int next = idx + 1;
        if (next < f.sig.size() && f.tok(next).is("<") && f.genericMatch[next] >= 0) {
            int[] tmp = new int[1];
            typeArgs = typeArgsBefore(f, f.genericMatch[next], ctxAt(f, idx), tmp);
            next = f.genericMatch[next] + 1;
        }
        if (next < f.sig.size() && f.tok(next).is("(") && f.match[next] >= 0) {
            argCount = countArgs(f, next, f.match[next]);
        }
        return bindCall(f, idx, argCount, typeArgs);
    }

    /** Bind an identifier that is used with the given number of call arguments (-1 if not invoked). */
    public Binding bindCall(Model.CsFile f, int idx, int argCount, List<Model.TypeRef> typeArgs) {
        Token t = f.tok(idx);
        String name = t.value;
        Ctx ctx = ctxAt(f, idx);
        Token prev = idx > 0 ? f.tok(idx - 1) : null;

        // object initializer member: new T { Name = ... }
        if (prev != null && (prev.is("{") || prev.is(",")) && idx + 1 < f.sig.size() && f.tok(idx + 1).is("=")) {
            int open = prev.is("{") ? idx - 1 : f.enclosing[idx];
            if (open >= 0 && f.tok(open).is("{") && f.initBrace[open]) {
                Model.TypeRef target = initializerTarget(f, open);
                return memberBinding(target, name, -1, typeArgs);
            }
        }

        if (prev != null && prev.isMemberAccessOp()) {
            if (idx < 2) return Binding.UNKNOWN;
            // receiver
            int recvEnd = idx - 2;
            Model.TypeRef recv;
            if (prev.is("::")) {
                Token alias = f.tok(recvEnd);
                recv = alias.isIdent("global") ? Model.TypeRef.namespace("") : exprEndingAt(f, recvEnd);
            } else {
                recv = exprEndingAt(f, recvEnd);
            }
            if (recv.kind == Model.TypeRef.Kind.NAMESPACE) {
                Model.TypeRef r = resolveQualified(recv, name, typeArgs, ctx);
                if (r.kind == Model.TypeRef.Kind.PROJECT) return new Binding(Binding.Kind.TYPE, null, null, r);
                if (r.kind == Model.TypeRef.Kind.NAMESPACE) return new Binding(Binding.Kind.NAMESPACE, null, null, r);
                return Binding.EXTERNAL;
            }
            return memberBinding(recv, name, argCount, typeArgs);
        }

        boolean typeContext = f.typeCtx[idx];
        if (!typeContext) {
            // locals
            Model.LocalVar lv = lookupLocal(ctx.member, name, idx);
            if (lv != null) return new Binding(Binding.Kind.LOCAL, lv, null, lv.type == null ? Model.TypeRef.UNKNOWN : lv.type);
            // members of the enclosing type chain
            for (Model.TypeDecl td = ctx.type; td != null; td = td.outer) {
                MemberHit h = findMember(selfRef(td), name, argCount, typeArgs);
                if (h != null && h != EXTERNAL_HIT) return hitBinding(h, typeArgs);
                if (h == EXTERNAL_HIT && td.outer == null) {
                    // inherited from an external base: could still be a type name in scope
                    Model.TypeRef tr = resolveSimpleType(name, typeArgs.size(), typeArgs, ctx);
                    if (tr.kind == Model.TypeRef.Kind.PROJECT) return new Binding(Binding.Kind.TYPE, null, null, tr);
                    return Binding.EXTERNAL;
                }
            }
            // using static
            if (ctx.file != null) {
                for (int[] us : ctx.file.usingStatics) {
                    Model.TypeRef st = resolveTypeTokens(ctx.file, us[0], us[1], new Ctx(ctx.file, null, null, null));
                    if (st.kind == Model.TypeRef.Kind.PROJECT) {
                        MemberHit h = findMember(st, name, argCount, typeArgs);
                        if (h != null && h != EXTERNAL_HIT) return hitBinding(h, typeArgs);
                    }
                }
            }
        }
        Model.TypeRef tr = resolveSimpleType(name, typeArgs.size(), typeArgs, ctx);
        switch (tr.kind) {
            case PROJECT:
                return new Binding(Binding.Kind.TYPE, null, null, tr);
            case NAMESPACE:
                return new Binding(Binding.Kind.NAMESPACE, null, null, tr);
            case TYPE_PARAM:
                return new Binding(Binding.Kind.TYPE_PARAM, null, null, tr);
            default:
                return Binding.EXTERNAL;
        }
    }

    private Binding memberBinding(Model.TypeRef recv, String name, int argCount, List<Model.TypeRef> typeArgs) {
        Model.TypeRef src = recv.memberSource();
        if (src.kind == Model.TypeRef.Kind.PROJECT) {
            MemberHit h = findMember(src, name, argCount, typeArgs);
            if (h != null && h != EXTERNAL_HIT) return hitBinding(h, typeArgs);
            return h == EXTERNAL_HIT ? Binding.EXTERNAL : Binding.UNKNOWN;
        }
        if (src.kind == Model.TypeRef.Kind.EXTERNAL) return Binding.EXTERNAL;
        return Binding.UNKNOWN;
    }

    private Binding hitBinding(MemberHit h, List<Model.TypeRef> typeArgs) {
        if (h.nestedType != null) return new Binding(Binding.Kind.TYPE, null, null, Model.TypeRef.project(h.nestedType, typeArgs));
        return new Binding(Binding.Kind.MEMBER, null, h.member, memberType(h, typeArgs));
    }

    /** All base types of a declaration, resolved. */
    public List<Model.TypeRef> resolvedBases(Model.TypeDecl t) {
        List<Model.TypeRef> out = new ArrayList<>();
        for (Model.TypeRange b : t.bases) out.add(resolveTypeRange(b, ctxOf(t, null)));
        return out;
    }
}
