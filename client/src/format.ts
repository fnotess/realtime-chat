const time = new Intl.DateTimeFormat(undefined, { hour: '2-digit', minute: '2-digit' });
const weekday = new Intl.DateTimeFormat(undefined, { weekday: 'long' });
const date = new Intl.DateTimeFormat(undefined, { day: 'numeric', month: 'short', year: 'numeric' });

export const formatTime = (ts: number) => time.format(ts);

function daysAgo(ts: number, now = Date.now()): number {
  const start = (t: number) => new Date(t).setHours(0, 0, 0, 0);
  // Rounded: a day with a DST change is 23 or 25 hours long.
  return Math.round((start(now) - start(ts)) / 86_400_000);
}

/** Chat list: the time today, then "Yesterday", the weekday within a week, then the date. */
export function formatListTime(ts: number): string {
  const d = daysAgo(ts);
  if (d <= 0) return time.format(ts);
  if (d === 1) return 'Yesterday';
  if (d < 7) return weekday.format(ts);
  return date.format(ts);
}

/** Separator between days in a chat. */
export function formatDay(ts: number): string {
  const d = daysAgo(ts);
  if (d <= 0) return 'Today';
  if (d === 1) return 'Yesterday';
  return d < 7 ? weekday.format(ts) : date.format(ts);
}

export const sameDay = (a: number, b: number) => daysAgo(a, b) === 0 && daysAgo(b, a) === 0;
