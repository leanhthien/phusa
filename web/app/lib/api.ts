// Mirrors vn.phusa.feed.ArticleFeedItem / ArticleFeedResponse on the backend.
export type ArticleFeedItem = {
  id: number;
  title: string;
  canonicalUrl: string;
  summary: string | null;
  imageUrl: string | null;
  publishedAt: string; // ISO-8601 instant
  sourceSlug: string;
  sourceName: string;
};

export type ArticleFeedResponse = {
  items: ArticleFeedItem[];
  nextCursor: string | null;
};

export async function fetchFeed(
  cursor: string | null,
  limit = 20,
): Promise<ArticleFeedResponse> {
  const params = new URLSearchParams({ limit: String(limit) });
  if (cursor) params.set("cursor", cursor);

  const res = await fetch(`/api/articles?${params.toString()}`, {
    cache: "no-store",
  });
  if (!res.ok) {
    throw new Error(`Feed request failed (${res.status})`);
  }
  return res.json();
}
