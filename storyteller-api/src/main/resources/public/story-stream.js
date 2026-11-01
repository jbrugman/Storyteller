/* Streamed content is always inserted as plain text. */
window.StoryStreaming = {
  async generate(url, body, conversation) {
    conversation.querySelector("[data-stream-preview]")?.remove();
    const empty = conversation.querySelector(".empty-state");
    if (empty) empty.hidden = true;
    const exchange = document.createElement("article");
    exchange.className = "exchange";
    exchange.dataset.streamPreview = "true";
    const prompt = document.createElement("div");
    prompt.className = "exchange-prompt message-content";
    prompt.textContent = body.get("prompt");
    const response = document.createElement("div");
    response.className = "exchange-response";
    const status = document.createElement("p");
    status.className = "message-role";
    status.setAttribute("role", "status");
    status.textContent = "Generating…";
    const text = document.createElement("div");
    text.className = "message-content";
    response.append(status, text);
    exchange.append(prompt, response);
    conversation.append(exchange);
    let reader;
    try {
      const result = await fetch(url, { method: "POST", body, credentials: "same-origin" });
      if (!result.ok || !result.body) throw new Error("Generation could not start.");
      reader = result.body.getReader();
      const decoder = new TextDecoder();
      let buffer = "";
      let completed = false;
      while (true) {
        const { value, done } = await reader.read();
        buffer += done ? decoder.decode() : decoder.decode(value, { stream: true });
        let end;
        while ((end = buffer.indexOf("\n\n")) >= 0) {
          const frame = buffer.slice(0, end);
          buffer = buffer.slice(end + 2);
          const event = frame.split("\n").find(line => line.startsWith("event: "))?.slice(7);
          const data = frame.split("\n").filter(line => line.startsWith("data: ")).map(line => line.slice(6)).join("\n");
          if (!data) continue;
          const payload = JSON.parse(data);
          if (event === "text") text.textContent = payload.text;
          if (event === "error") throw new Error(payload.message);
          if (event === "done") { text.textContent = payload.response; completed = true; }
          conversation.scrollTop = conversation.scrollHeight;
        }
        if (done) break;
      }
      if (!completed) throw new Error("The stream ended before completion.");
    } catch (error) {
      if (!text.textContent.trim()) {
        exchange.remove();
        if (empty) empty.hidden = false;
      } else {
        status.textContent = "Incomplete response — " + error.message;
        status.setAttribute("role", "alert");
      }
      throw error;
    } finally {
      if (reader) { await reader.cancel().catch(() => {}); reader.releaseLock(); }
    }
  }
};
