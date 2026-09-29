import type { Tick } from '../chat/ChatClient';

const LABEL: Record<Tick, string> = {
  pending: 'Waiting to send',
  sent: 'Sent',
  delivered: 'Delivered',
  read: 'Read',
  failed: 'Not sent',
};

const GLYPH: Record<Tick, string> = { pending: '🕓', sent: '✓', delivered: '✓✓', read: '✓✓', failed: '!' };

export function Ticks({ tick }: { tick: Tick }) {
  return (
    <span className={`tick tick-${tick}`} role="img" aria-label={LABEL[tick]} title={LABEL[tick]}>
      {GLYPH[tick]}
    </span>
  );
}
