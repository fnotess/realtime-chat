import { useChatState } from '../state/ChatContext';
import { ChatPane } from './ChatPane';
import { ConnectionBanner } from './ConnectionBanner';
import { Sidebar } from './Sidebar';

export function ChatLayout() {
  const { open } = useChatState();
  return (
    // On narrow screens only one column shows; "has-open" picks which (see styles.css).
    <div className={`layout${open ? ' has-open' : ''}`}>
      <ConnectionBanner />
      <Sidebar />
      <main className="pane">
        {/* Keyed by chat so scroll position, composer text and focus start fresh per chat. */}
        {open ? (
          <ChatPane key={open.other} />
        ) : (
          <div className="pane-empty">
            <p>Select a chat, or start a new one by username.</p>
          </div>
        )}
      </main>
    </div>
  );
}
