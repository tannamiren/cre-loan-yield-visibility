"use client";

export default function Error({
  error,
  reset,
}: {
  error: Error & { digest?: string };
  reset: () => void;
}) {
  return (
    <main className="mx-auto max-w-5xl p-6">
      <h1 className="text-2xl font-bold text-red-700">Something went wrong</h1>
      <p className="mt-2 text-gray-600">
        Couldn&apos;t reach the API. Confirm the Spring Boot app is running
        (<code>./run.sh</code> from the repo root), then try again.
      </p>
      <button
        type="button"
        onClick={reset}
        className="mt-4 rounded bg-gray-800 px-3 py-1.5 text-sm text-white hover:bg-gray-700"
      >
        Retry
      </button>
      <pre className="mt-4 text-xs text-gray-400">{error.message}</pre>
    </main>
  );
}
