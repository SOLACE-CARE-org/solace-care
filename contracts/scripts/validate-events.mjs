// Compiles every event schema against the envelope and checks each example
// in events/examples/ against the schema named by its type and version.
import { readFileSync, readdirSync, statSync } from "node:fs";
import { join, relative } from "node:path";
import { fileURLToPath } from "node:url";
import Ajv2020 from "ajv/dist/2020.js";
import addFormats from "ajv-formats";

const root = fileURLToPath(new URL("../events/", import.meta.url));
const ajv = new Ajv2020({ strict: true, allErrors: true });
addFormats(ajv);

const files = (dir) =>
  readdirSync(dir).flatMap((name) => {
    const path = join(dir, name);
    return statSync(path).isDirectory() ? files(path) : [path];
  });

const schemaFiles = files(root).filter((f) => f.endsWith(".schema.json"));
for (const file of schemaFiles) {
  ajv.addSchema(JSON.parse(readFileSync(file, "utf8")));
}

let failures = 0;
for (const file of schemaFiles) {
  const { $id } = JSON.parse(readFileSync(file, "utf8"));
  try {
    ajv.getSchema($id);
    console.log(`ok      ${relative(root, file)}`);
  } catch (e) {
    failures++;
    console.error(`invalid ${relative(root, file)}: ${e.message}`);
  }
}

for (const file of files(join(root, "examples")).filter((f) => f.endsWith(".json"))) {
  const event = JSON.parse(readFileSync(file, "utf8"));
  const [producer, ...rest] = event.type.split(".");
  const id = `https://schemas.solace.care/events/${producer}/${rest.join(".")}.v${event.version}.schema.json`;
  const validate = ajv.getSchema(id);
  if (!validate) {
    failures++;
    console.error(`no schema for ${relative(root, file)} (${id})`);
  } else if (!validate(event)) {
    failures++;
    console.error(`invalid ${relative(root, file)}: ${ajv.errorsText(validate.errors)}`);
  } else {
    console.log(`ok      ${relative(root, file)}`);
  }
}

if (failures > 0) {
  console.error(`${failures} event schema problem(s)`);
  process.exit(1);
}
