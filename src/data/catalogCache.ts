/** Bounded LRU. Expired in-flight entries stay pinned until their owner completes. */
export class CatalogCache<T extends { busy: boolean }> {
  private readonly values = new Map<string, { value: T; touched: number }>();
  constructor(private readonly limit = 12, private readonly ttl = 10 * 60_000,
              private readonly clock: () => number = Date.now) {}
  get(key: string): T | undefined {
    const entry = this.values.get(key);
    if (!entry) return undefined;
    if (!entry.value.busy && this.clock() - entry.touched >= this.ttl) {
      this.values.delete(key); return undefined;
    }
    this.values.delete(key); this.values.set(key, entry);
    return entry.value;
  }
  has(key: string) { return this.get(key) !== undefined; }
  set(key: string, value: T) {
    this.values.delete(key);
    this.values.set(key, { value, touched: this.clock() });
    this.prune();
  }
  prune() {
    for (const [key, entry] of this.values) {
      if (!entry.value.busy && (this.values.size > this.limit || this.clock() - entry.touched >= this.ttl)) this.values.delete(key);
    }
  }
}
