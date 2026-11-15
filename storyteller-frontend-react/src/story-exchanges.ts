import type { StoryMessage } from './api';

export interface Exchange {
  prompt?: StoryMessage;
  response?: StoryMessage;
}

// The message a story turn edits. React pairs the assistant message onto the exchange, so its index is the
// assistant's own and the edit targets it unchanged; the built-in JTE form has only the user index and adds
// one. Keeping the value here lets the pairing tests pin the exact index, which is what broke the editor.
export function editAssistantIndex(exchange: Exchange): number {
  return exchange.response!.messageIndex;
}

// Pair by role and adjacent persisted indexes, keeping unmatched messages visible.
export function pairExchanges(messages: StoryMessage[]): Exchange[] {
  const exchanges: Exchange[] = [];
  for (const message of messages) {
    const previous = exchanges[exchanges.length - 1];
    if (message.role === 'assistant' && previous?.prompt && !previous.response &&
      previous.prompt.messageIndex + 1 === message.messageIndex) previous.response = message;
    else exchanges.push(message.role === 'user' ? { prompt: message } : { response: message });
  }
  return exchanges;
}
