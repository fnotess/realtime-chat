import { ChatLayout } from './components/ChatLayout';
import { LoginScreen } from './components/LoginScreen';
import { useChatState } from './state/ChatContext';

export function App() {
  const { auth } = useChatState();
  if (auth === 'checking') return <div className="splash" aria-busy="true" />;
  return auth === 'loggedIn' ? <ChatLayout /> : <LoginScreen />;
}
