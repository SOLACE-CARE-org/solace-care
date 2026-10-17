// Fails on a breaking change to an event schema, compared with a base git ref (default
// origin/main, or main when there is no remote). Within one major version (one schema file),
// consumers must keep reading events produced before the change, and producers must keep producing
// events the old consumers accept:
//   - a schema file must not disappear (publish a new .vN file beside it instead)
//   - a property must not be removed, change type, or change its const/enum to something narrower
//   - a property must not become required if it was optional
//   - a new required property may not be added
// Adding an optional property is fine. A breaking change goes in a new file: <name>.v<N+1>.schema.json.
//
// Usage: node scripts/check-event-compat.mjs [baseRef]
import { execFileSync } from "node:child_process";
import { existsSync, readFileSync, readdirSync, statSync } from "node:fs";
import { join, relative, sep } from "node:path";
import { fileURLToPath } from "node:url";

const contracts = fileURLToPath(new URL("..", import.meta.url));
const eventsDir = join(contracts, "events");
const gitIn = (cwd) => (...args) =>
  execFileSync("git", args, { cwd, encoding: "utf8", stdio: ["ignore", "pipe", "pipe"] });
const repoRoot = gitIn(contracts)("rev-parse", "--show-toplevel").trim();
// Paths below are relative to the repository root, so git runs from there.
const git = gitIn(repoRoot);

const baseRef = process.argv[2] ?? (refExists("origin/main") ? "origin/main" : "main");
function refExists(ref) {
  try { git("rev-parse", "--verify", "--quiet", ref); return true; } catch { return false; }
}

const toRepoPath = (abs) => relative(repoRoot, abs).split(sep).join("/");

const baseFiles = git("ls-tree", "-r", "--name-only", baseRef, "--", toRepoPath(eventsDir))
  .split("\n").filter((f) => f.endsWith(".schema.json"));
const baseSchema = (path) => JSON.parse(git("show", `${baseRef}:${path}`));

const problems = [];

// Collect every property path with its definition, following allOf and nested objects.
function properties(schema, prefix = "", out = new Map(), required = new Set()) {
  for (const part of schema.allOf ?? []) {
    if (!part.$ref) properties(part, prefix, out, required);
  }
  for (const name of schema.required ?? []) required.add(prefix + name);
  for (const [name, def] of Object.entries(schema.properties ?? {})) {
    const path = prefix + name;
    out.set(path, def);
    if (def && (def.properties || def.allOf)) properties(def, path + ".", out, required);
  }
  return { props: out, required };
}

const typesOf = (def) => {
  if (def?.const !== undefined) return [`const:${JSON.stringify(def.const)}`];
  const t = def?.type ?? (def?.enum ? "enum" : "any");
  return (Array.isArray(t) ? t : [t]).map(String).sort();
};

for (const path of baseFiles) {
  const local = join(repoRoot, path);
  if (!existsSync(local)) {
    problems.push(`${path}: removed. Keep it until no consumer reads it; add a new .vN file instead.`);
    continue;
  }
  const before = properties(baseSchema(path));
  const after = properties(JSON.parse(readFileSync(local, "utf8")));

  for (const [prop, def] of before.props) {
    if (!after.props.has(prop)) {
      problems.push(`${path}: property "${prop}" removed`);
      continue;
    }
    const was = typesOf(def);
    const now = typesOf(after.props.get(prop));
    if (!was.every((t) => now.includes(t))) {
      problems.push(`${path}: property "${prop}" changed from ${was.join("|")} to ${now.join("|")}`);
    }
    if (Array.isArray(def?.enum)) {
      const nowEnum = after.props.get(prop)?.enum ?? [];
      const dropped = def.enum.filter((v) => !nowEnum.includes(v));
      if (dropped.length) problems.push(`${path}: property "${prop}" no longer allows ${JSON.stringify(dropped)}`);
    }
  }
  for (const prop of after.required) {
    if (!before.required.has(prop)) {
      problems.push(`${path}: property "${prop}" is newly required`);
    }
  }
}

const allNow = [];
(function walk(dir) {
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    if (statSync(p).isDirectory()) walk(p);
    else if (name.endsWith(".schema.json")) allNow.push(toRepoPath(p));
  }
})(eventsDir);

if (problems.length) {
  console.error(`event schemas: ${problems.length} breaking change(s) against ${baseRef}\n  ${problems.join("\n  ")}`);
  process.exit(1);
}
console.log(`event schemas: ${baseFiles.length} schema(s) on ${baseRef}, ${allNow.length} now, no breaking changes`);
