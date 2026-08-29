# Simple-Obfuscator

A source-level name obfuscator for the C# scripts of a Unity project. It renames
your classes, structs, enums, methods, fields, properties, parameters and local
variables to short meaningless names (`a`, `b`, `c`, …) to make decompiled or
extracted scripts harder to read, while **keeping every name that Unity, the
inspector, or reflection resolves by string** so the game still runs.

This is a rewrite of the original regex-based prototype. It now works by
tokenizing and lightly parsing the C# so that renaming is scope-aware and
consistent across every file in the project.

## What it protects (never renames)

Renaming something Unity looks up by name would silently break your game, so the
tool keeps:

- **`[SerializeField]` and public fields** on `MonoBehaviour` / `ScriptableObject`
  / `[Serializable]` types — these are stored by name in scenes, prefabs and
  assets, and shown in the inspector.
- **Unity message methods** — `Awake`, `Start`, `Update`, `OnCollisionEnter2D`,
  `OnTriggerEnter2D`, `OnPointerDown`, editor callbacks, … (invoked by name).
- **Public methods/properties/events of Unity-derived types** — these are the
  targets of `UnityEvent`s (button `onClick`, animation events, etc.), which are
  stored by name. Rename them with `--aggressive` if you don't use those.
- **Anything whose name appears inside a string literal** — a strong signal it is
  used with `Invoke("Foo")`, `SendMessage`, `animator.SetBool("grounded", …)`,
  reflection, shader property names, and so on. Disable with `--no-string-protection`.
- **`override` members of external base types, explicit interface implementations,
  members of types implementing external interfaces, attribute classes,
  `extern`/`DllImport`, `[Preserve]`,** and well-known .NET members (`ToString`,
  `Equals`, `GetEnumerator`, …).
- Any name whose uses cannot all be resolved to a project declaration (ambiguous),
  and any name you pass to `--keep`.

Everything else — private/internal implementation names, project-only types and
their private members, and all locals/parameters — is renamed, consistently
across every file.

### The `.meta` files matter

Unity references scripts by the GUID in each script's `.cs.meta` file, not by the
class name, so **renaming a script file is safe as long as its `.meta` goes with
it**. The tool renames a `Foo.cs` file to match its renamed class and copies the
original `Foo.cs.meta` alongside it, preserving the GUID and all scene/prefab
references. Copy the obfuscated `Scripts` folder back over the original (replacing
both `.cs` and `.meta`) — do **not** let Unity regenerate the `.meta` files.

## Requirements

- JDK 17 or newer (`javac`, `java`). No external libraries.

## Build & run

```bash
./build.sh                                             # compiles into ./out
./run.sh src/filesForObfuscation src/ObfuscatedFiles --clean
```

`run.sh` rebuilds automatically when sources change. Or manually:

```bash
javac -d out src/*.java
java -cp out Main <inputDir> <outputDir> [options]
```

With no arguments it defaults to `src/filesForObfuscation` → `src/ObfuscatedFiles`
(the bundled sample is Unity's 2D Platformer microgame).

Always point `<outputDir>` at a **new** folder, review the result (and the
generated `obfuscation-map.txt`), and test the game before shipping.

## Options

| Option | Effect |
|---|---|
| `--keep <names>` | Comma-separated names or globs (e.g. `On*`) to never rename |
| `--keep-file <file>` | Read keep names from a file, one per line |
| `--aggressive` | Also rename public methods/properties/events of Unity types (breaks `UnityEvent`/animation-event bindings to them) |
| `--rename-public-fields` | Rename public fields of plain (non-Unity, non-`[Serializable]`) classes |
| `--keep-enums` | Don't rename enum types or members |
| `--keep-delegates` | Don't rename delegate types |
| `--no-locals` | Don't rename parameters and local variables |
| `--keep-comments` | Keep comments (stripped by default) |
| `--no-string-protection` | Rename names even if they appear in strings (risky) |
| `--prefix <p>` | Prefix for generated names |
| `--map <file>` | Where to write the rename map (`none` to skip) |
| `--clean` | Delete the output folder first |
| `--no-copy` | Don't copy non-`.cs` files (`.meta`, assets) to the output |
| `--dry-run` | Analyse and print the plan without writing |
| `--verbose` | Print what was kept and why |
| `--help` | Full help |

## How it works

1. **Lex** each `.cs` file losslessly (comments, preprocessor lines, verbatim/raw/
   interpolated strings, generics, etc.), so output is byte-for-byte reproducible.
2. **Parse** declarations: namespaces, `using`s, types (merging `partial`s),
   members, parameters and block-scoped locals, attributing every identifier token
   to what it belongs to.
3. **Resolve** each identifier use to a project declaration, an external symbol, or
   "unknown", inferring receiver types through project types, generics, arrays and
   common collections.
4. **Analyze**: apply the keep policies above; a name is renamed only if *every*
   declaration and *every* use of it stays inside the project and passes policy.
5. **Rewrite** tokens (globally consistent names for types/members; per-method
   fresh names for locals) and write the obfuscated tree plus the rename map.

If any file fails to parse, the tool writes nothing and reports the error, rather
than risk emitting code that doesn't compile.

## Limitations

- It's a lightweight resolver, not a full C# compiler. It is deliberately
  conservative: when unsure, it keeps the name. It does not read your `.asmdef`s or
  external DLLs, so cross-assembly `internal`s referenced from outside the obfuscated
  folder should be added to `--keep`.
- Obfuscate the whole scripts folder at once so cross-file references stay
  consistent. Names referenced only from scenes/prefabs via `UnityEvent` are kept by
  default (see `--aggressive`).
- This raises the effort to read extracted scripts; it is not a security boundary.
  Never keep secrets in client builds.
