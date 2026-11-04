// Next.js Windows static-export segment paths can contain directory separators
// where the browser requests dots. Keep originals and add the expected aliases.
// Upstream: https://github.com/vercel/next.js/issues/85374
import { readdir, readFile, writeFile } from "node:fs/promises";
import path from "node:path";

const root = path.resolve("out");
async function visit(directory) {
  for (const entry of await readdir(directory, { withFileTypes: true })) {
    const full = path.join(directory, entry.name);
    if (entry.isDirectory()) await visit(full);
    else if (entry.name.endsWith(".txt")) {
      const parts = path.relative(root, full).split(path.sep);
      const index = parts.findIndex(part => part.startsWith("__next."));
      if (index >= 0 && index < parts.length - 1) {
        const alias = path.join(root, ...parts.slice(0, index), parts.slice(index).join("."));
        await writeFile(alias, await readFile(full));
      }
    }
  }
}
await visit(root);
