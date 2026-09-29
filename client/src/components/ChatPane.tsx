import { useChatClient, useChatState } from '../state/ChatContext';
import { Avatar } from './Avatar';
import { Composer } from './Composer';
import { MessageList } from './MessageList';

export function ChatPane() {
  const client = useChatClient();
  const { open } = useChatState();
  if (!open) return null;
  return (
    <>
      <header className="pane-header">
        <button className="back" onClick={() => client.openChat(null)} aria-label="Back to chats">
          ‹
        </button>
        <Avatar name={open.other} size={36} />
        <h2>{open.other}</h2>
      </header>
      <MessageList view={open} />
      <Composer />
    </>
  );
}
