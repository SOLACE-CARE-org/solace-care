// Fails on a breaking change to an edge OpenAPI spec, compared with a base git ref (default
// origin/main, or main when there is no remote), using oasdiff in Docker so local runs and CI use
// the same binary. A spec that is new on this branch has nothing to break.
//
// Usage: node scripts/check-openapi-breaking.mjs [baseRef]
import { execFileSync, spawnSync } from "node:child_process";
import { mkdtempSync, readdirSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";

const OASDIFF = "tufin/oasdiff:v1.33.0";
const contracts = fileURLToPath(new URL("..", import.meta.url));
const git = (...args) =>
  execFileSync("git", args, { cwd: contracts, encoding: "utf8", stdio: ["ignore", "pipe", "pipe"] });
const refExists = (ref) => {
  try { git("rev-parse", "--verify", "--quiet", ref); return true; } catch { return false; }
};
const baseRef = process.argv[2] ?? (refExists("origin/main") ? "origin/main" : "main");

const baseDir = mkdtempSync(join(tmpdir(), "openapi-base-"));
const headDir = join(contracts, "openapi");
let failures = 0;
let compared = 0;

for (const file of readdirSync(headDir).filter((f) => f.endsWith(".yaml"))) {
  let base;
  try {
    base = git("show", `${baseRef}:./openapi/${file}`);
  } catch {
    console.log(`openapi: ${file} is new on this branch`);
    continue;
  }
  writeFileSync(join(baseDir, file), base);
  compared++;
  const result = spawnSync(
    "docker",
    ["run", "--rm", "-v", `${baseDir}:/base:ro`, "-v", `${headDir}:/head:ro`, OASDIFF,
      "breaking", `/base/${file}`, `/head/${file}`, "--fail-on", "ERR", "--format", "text"],
    { encoding: "utf8", env: { ...process.env, MSYS_NO_PATHCONV: "1" } },
  );
  const output = `${result.stdout ?? ""}${result.stderr ?? ""}`.trim();
  if (result.status !== 0) {
    failures++;
    console.error(`openapi: ${file} has breaking changes against ${baseRef}\n${output}`);
  } else if (output) {
    console.log(`openapi: ${file}: ${output}`);
  }
}

if (failures > 0) process.exit(1);
console.log(`openapi: ${compared} spec(s) compared with ${baseRef}, no breaking changes`);
