// Test double for Cloudflare D1 backed by real SQLite (node:sqlite), so the production SQL and the real migration are exercised.
import { readFileSync } from "node:fs";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";
import type { D1Like, D1Statement } from "../src/music/store";

// Loaded through require: Vite's module graph does not know the experimental `node:sqlite` built-in.
const { DatabaseSync } = createRequire(import.meta.url)("node:sqlite") as { DatabaseSync: new (path: string) => any };

class Statement implements D1Statement {
  private values: unknown[] = [];
  constructor(private readonly db: any, readonly sql: string) {}
  bind(...values: unknown[]): D1Statement {
    const copy = new Statement(this.db, this.sql);
    copy.values = values.map((v) => (v === undefined ? null : v));
    return copy;
  }
  async all<T>() { return { results: this.db.prepare(this.sql).all(...this.values) as T[] }; }
  async first<T>() { return (this.db.prepare(this.sql).get(...this.values) as T | undefined) ?? null; }
  async run() { return this.db.prepare(this.sql).run(...this.values); }
}

export class TestD1 implements D1Like {
  readonly db = new DatabaseSync(":memory:");
  constructor() {
    this.db.exec(readFileSync(fileURLToPath(new URL("../migrations/0001_music.sql", import.meta.url)), "utf8"));
  }
  prepare(sql: string): D1Statement { return new Statement(this.db, sql); }
  private queue: Promise<unknown> = Promise.resolve();
  /** D1 batches are transactional and serialised: all statements apply or none do, and two batches never interleave. */
  batch(statements: D1Statement[]): Promise<unknown[]> {
    const run = async () => {
      this.db.exec("BEGIN");
      try {
        const out: unknown[] = [];
        for (const s of statements) out.push(await s.run());
        this.db.exec("COMMIT");
        return out;
      } catch (e) { this.db.exec("ROLLBACK"); throw e; }
    };
    const result = this.queue.then(run, run);
    this.queue = result.catch(() => undefined);
    return result;
  }
  rows<T = Record<string, unknown>>(sql: string, ...params: unknown[]): T[] { return this.db.prepare(sql).all(...params) as T[]; }
}
