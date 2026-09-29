import { createContext, useContext, useEffect, useState, useSyncExternalStore, type ReactNode } from 'react';
import { ChatClient, type ChatSnapshot } from '../chat/ChatClient';
import { httpApi } from '../chat/api';
import { wsUrl } from '../config';

// ChatClient owns all chat state; components read it through useSyncExternalStore, React's own
// hook for external stores. Copying it into useReducer would give two sources of truth to keep in
// step. The context only carries the one client instance.

const ClientContext = createContext<ChatClient | null>(null);

export function ChatProvider({ children }: { children: ReactNode }) {
  const [client] = useState(
    () => new ChatClient({ api: httpApi(), wsUrl: wsUrl(), storage: window.sessionStorage }),
  );

  useEffect(() => {
    void client.start();
    const onVisibility = () => client.setVisible(document.visibilityState === 'visible');
    const onOnline = () => client.setBrowserOnline(true);
    const onOffline = () => client.setBrowserOnline(false);
    onVisibility();
    if (!navigator.onLine) onOffline();
    document.addEventListener('visibilitychange', onVisibility);
    window.addEventListener('online', onOnline);
    window.addEventListener('offline', onOffline);
    return () => {
      document.removeEventListener('visibilitychange', onVisibility);
      window.removeEventListener('online', onOnline);
      window.removeEventListener('offline', onOffline);
    };
  }, [client]);

  return <ClientContext.Provider value={client}>{children}</ClientContext.Provider>;
}

export function useChatClient(): ChatClient {
  const client = useContext(ClientContext);
  if (!client) throw new Error('useChatClient outside ChatProvider');
  return client;
}

export function useChatState(): ChatSnapshot {
  const client = useChatClient();
  return useSyncExternalStore(client.subscribe, client.getSnapshot);
}
