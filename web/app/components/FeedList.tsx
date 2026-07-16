"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { fetchFeed, type ArticleFeedItem } from "../lib/api";

function timeAgo(iso: string): string {
  const then = new Date(iso).getTime();
  const mins = Math.round((Date.now() - then) / 60000);
  if (mins < 1) return "just now";
  if (mins < 60) return `${mins}m ago`;
  const hrs = Math.round(mins / 60);
  if (hrs < 24) return `${hrs}h ago`;
  const days = Math.round(hrs / 24);
  return `${days}d ago`;
}

export default function FeedList() {
  const [items, setItems] = useState<ArticleFeedItem[]>([]);
  const [cursor, setCursor] = useState<string | null>(null);
  const [done, setDone] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [atSentinel, setAtSentinel] = useState(false);
  // Guards against duplicate concurrent loads (incl. React StrictMode double-mount).
  const inFlight = useRef(false);
  const sentinel = useRef<HTMLDivElement>(null);

  const loadMore = useCallback(async () => {
    if (inFlight.current || done) return;
    inFlight.current = true;
    setError(null);
    try {
      const page = await fetchFeed(cursor);
      setItems((prev) => [...prev, ...page.items]);
      if (page.nextCursor) setCursor(page.nextCursor);
      else setDone(true);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      inFlight.current = false;
    }
  }, [cursor, done]);

  // Deterministic first load on mount (don't rely on the observer's initial
  // callback timing, which races with hydration).
  useEffect(() => {
    loadMore();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // One observer, created once: it only tracks whether the sentinel is near the
  // viewport, as state. Driving loads off an edge-triggered observer callback is
  // fragile (it can miss the "still visible after a load" case); a state flag lets
  // the effect below re-check and chain reliably.
  useEffect(() => {
    const el = sentinel.current;
    if (!el) return;
    const io = new IntersectionObserver(
      (entries) => setAtSentinel(entries[0].isIntersecting),
      { rootMargin: "600px" },
    );
    io.observe(el);
    return () => io.disconnect();
  }, []);

  // Load more whenever the sentinel is in view and there's more to fetch. Depending
  // on items.length re-runs this after each page: if the sentinel is still in view,
  // it keeps loading until it isn't (or the feed ends).
  useEffect(() => {
    if (atSentinel && !done) loadMore();
  }, [atSentinel, done, loadMore, items.length]);

  return (
    <div className="flex flex-col divide-y divide-black/8 dark:divide-white/10">
      {items.map((a) => (
        <article key={a.id} className="py-4">
          <a
            href={a.canonicalUrl}
            target="_blank"
            rel="noopener noreferrer"
            className="text-base font-medium leading-snug hover:underline"
          >
            {a.title}
          </a>
          {a.summary ? (
            <p className="mt-1 line-clamp-2 text-sm text-black/60 dark:text-white/55">
              {a.summary}
            </p>
          ) : null}
          <div className="mt-1.5 text-xs text-black/45 dark:text-white/40">
            {a.sourceName} · {timeAgo(a.publishedAt)}
          </div>
        </article>
      ))}

      {error ? (
        <div className="py-4 text-sm text-red-600 dark:text-red-400">
          {error} —{" "}
          <button className="underline" onClick={() => loadMore()}>
            retry
          </button>
        </div>
      ) : null}

      {done && items.length === 0 ? (
        <div className="py-10 text-center text-sm text-black/45 dark:text-white/40">
          No articles yet.
        </div>
      ) : null}

      {/* Infinite-scroll trigger + end-of-feed marker. */}
      <div ref={sentinel} className="py-6 text-center text-xs text-black/35 dark:text-white/30">
        {done ? (items.length > 0 ? "— end —" : "") : "Loading…"}
      </div>

      {/* Fallback for when infinite scroll can't fire (no IntersectionObserver,
          keyboard-only, etc.). Harmless alongside the observer. */}
      {!done && items.length > 0 ? (
        <div className="pb-10 text-center">
          <button
            onClick={() => loadMore()}
            className="rounded-md border border-black/15 px-4 py-1.5 text-sm hover:bg-black/5 dark:border-white/20 dark:hover:bg-white/10"
          >
            Load more
          </button>
        </div>
      ) : null}
    </div>
  );
}
