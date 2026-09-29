import { useChatClient, useChatState } from '../state/ChatContext';
import { Avatar } from './Avatar';
import { ChatList } from './ChatList';
import { NewChatForm } from './NewChatForm';

export function Sidebar() {
  const client = useChatClient();
  const { me } = useChatState();
  return (
    <aside className="sidebar">
      <header className="sidebar-header">
        <Avatar name={me ?? '?'} size={36} />
        <span className="me">{me}</span>
        <button className="link" onClick={() => void client.logout()}>
          Log out
        </button>
      </header>
      <NewChatForm />
      <ChatList />
    </aside>
  );
}
