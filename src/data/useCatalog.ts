import { useEffect, useMemo, useReducer, useRef } from "react";
import { ContentProvider, mergePage, Series } from "../domain/model";

import { CatalogCache } from "./catalogCache";

type Page = {
  items: Series[];
  page: number;
  more: boolean;
  busy: boolean;
  error: string;
};
const empty = (): Page => ({
  items: [],
  page: 0,
  more: true,
  busy: false,
  error: "",
});

/** The catalog stays alive across detail/playback; each query owns its pagination. */
export function useCatalog(
  provider: ContentProvider,
  query: string | null,
  enabled: boolean
) {
  const cache = useMemo(() => new CatalogCache<Page>(), [provider]);
  const key = query === null ? "home" : `search:${query}`;
  const [, redraw] = useReducer((n: number) => n + 1, 0);
  const alive = useRef(true);
  useEffect(() => {
    alive.current = true;
    return () => {
      alive.current = false;
    };
  }, []);
  const entry = cache.get(key) ?? empty();
  async function load(replace = false) {
    const previous = cache.get(key) ?? empty();
    if (previous.busy || (!replace && !previous.more)) return;
    const next = { ...previous, busy: true, error: "" };
    cache.set(key, next);
    redraw();
    try {
      const page = replace ? 1 : previous.page + 1;
      const rows =
        query === null
          ? await provider.home(page)
          : await provider.search(query, page);
      Object.assign(next, {
        items: (replace ? rows : mergePage(previous.items, rows)).slice(-300),
        page,
        more: rows.length > 0,
      });
    } catch (e) {
      next.error = e instanceof Error ? e.message : "内容加载失败";
    } finally {
      next.busy = false;
      cache.set(key, next);
      if (alive.current) redraw();
    }
  }
  useEffect(() => {
    if (enabled && !cache.has(key) && query !== "") void load();
  }, [cache, key, enabled]);
  return { ...entry, loadMore: () => load(), refresh: () => load(true) };
}
