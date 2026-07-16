import FeedList from "./components/FeedList";

export default function Home() {
  return (
    <main className="mx-auto max-w-2xl px-4 py-10">
      <header className="mb-2">
        <h1 className="text-2xl font-semibold tracking-tight">Phù Sa</h1>
        <p className="mt-1 text-sm text-black/55 dark:text-white/45">
          Vietnamese tech &amp; startup news — crawled, deduped, settled.
        </p>
      </header>
      <FeedList />
    </main>
  );
}
