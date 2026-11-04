import type { StoryMessage } from './api';

export interface Exchange {
  prompt?: StoryMessage;
  response?: StoryMessage;
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
