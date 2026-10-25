import { test } from "node:test";
import assert from "node:assert/strict";
import { CatalogCache } from "../src/data/catalogCache";
test("catalog cache evicts least recently read completed query", () => {
  const cache = new CatalogCache<{ busy: boolean }>(2);
  cache.set("a", { busy: false }); cache.set("b", { busy: false });
  cache.get("a"); cache.set("c", { busy: false });
  assert.equal(cache.has("a"), true); assert.equal(cache.has("b"), false);
});
test("expired queries reload but in-flight work is pinned until completion", () => {
  let now = 0; const cache = new CatalogCache<{ busy: boolean }>(1, 100, () => now);
  cache.set("busy", { busy: true }); now = 101;
  assert.equal(cache.has("busy"), true);
  cache.set("done", { busy: false });
  assert.equal(cache.has("busy"), true);
  cache.set("busy", { busy: false });
  assert.equal(cache.has("done"), false);
  now = 202; assert.equal(cache.has("busy"), false);
});
