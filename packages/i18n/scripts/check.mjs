// Checks the EN and FR catalogues (CLAUDE.md, Language: "CI fails on a missing key").
//   - every key exists in both languages, with a non-empty string
//   - both use the same {{placeholders}}
//   - no exclamation marks and no emoji (design-system README, Content fundamentals)
// Exits non-zero and lists every problem.
import { readFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const load = (lang) => JSON.parse(readFileSync(resolve(here, `../locales/${lang}.json`), "utf8"));

const flatten = (object, prefix = "") =>
  Object.entries(object).flatMap(([key, value]) =>
    value !== null && typeof value === "object"
      ? flatten(value, `${prefix}${key}.`)
      : [[`${prefix}${key}`, value]],
  );

const catalogues = { en: new Map(flatten(load("en"))), fr: new Map(flatten(load("fr"))) };
const problems = [];
const placeholders = (text) => [...String(text).matchAll(/\{\{\s*(\w+)\s*\}\}/g)].map((m) => m[1]).sort();
const emoji = /\p{Extended_Pictographic}/u;

for (const [lang, other] of [["en", "fr"], ["fr", "en"]]) {
  for (const [key, value] of catalogues[lang]) {
    if (!catalogues[other].has(key)) problems.push(`${key}: missing in ${other}.json`);
    if (typeof value !== "string" || value.trim() === "") problems.push(`${key}: empty in ${lang}.json`);
    if (String(value).includes("!")) problems.push(`${key}: exclamation mark in ${lang}.json`);
    if (emoji.test(String(value))) problems.push(`${key}: emoji in ${lang}.json`);
  }
}
for (const [key, en] of catalogues.en) {
  const fr = catalogues.fr.get(key);
  if (fr !== undefined && placeholders(en).join() !== placeholders(fr).join()) {
    problems.push(`${key}: placeholders differ (en ${placeholders(en)}; fr ${placeholders(fr)})`);
  }
}

if (problems.length > 0) {
  console.error(`i18n: ${problems.length} problem(s)\n  ${[...new Set(problems)].join("\n  ")}`);
  process.exit(1);
}
console.log(`i18n: ${catalogues.en.size} keys, EN and FR match`);
