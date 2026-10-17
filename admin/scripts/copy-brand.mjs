// Copies the logos the portal uses from design/design-system/assets/Logos into public/brand, so the
// design folder stays the only source (Angular serves assets from inside the project only).
import { copyFileSync, mkdirSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const from = resolve(here, "../../design/design-system/assets/Logos/png");
const to = resolve(here, "../public/brand");
mkdirSync(to, { recursive: true });
for (const file of ["solace-care-logo.png"]) copyFileSync(resolve(from, file), resolve(to, file));
