(() => {
  // Demo state stays in this tab and is separate from real Storyteller sessions.
  const stateKey = "storyteller_wireframe_story";
  const demoReply = `The last light of evening slipped across the terrace of the beach house at L’Horizon. Valerie Thorne sat barefoot with a mug between her hands, listening to the waves instead of the roar of an audience. Somewhere beyond the gardens, the studio lights were still on.

“You escaped,” Merel said, dropping into the chair beside her.

“Strategic retreat,” Valerie corrected. “Very different. Much better branding.”

Roos appeared in the doorway carrying three bowls. “Good. Your strategic retreat can help with dinner.”

Valerie eyed the bowls. “Is this a meal or a percussion experiment?”

“Keep talking and you’ll find out.”

Their laughter settled into the sheltered warmth of the terrace. For a moment, Valerie watched the sea without smiling. Tomorrow there would be rehearsals, decisions, and a room full of people waiting for the frontwoman of The Eclipse to know exactly what came next.

Merel nudged her foot beneath the table. “You know you don’t have to give us a performance.”

Valerie glanced at her, a joke already forming. Then she let it go.

“I know,” she said quietly.

Roos put a bowl into her hands. The sea breeze smelled of salt, dinner was warm, and neither of her friends seemed to need anything more from her.`;
  let state = { title: "Demo story", exchanges: [] };
  try {
    const stored = JSON.parse(sessionStorage.getItem(stateKey));
    if (stored && typeof stored.title === "string" && Array.isArray(stored.exchanges)) {
      state = stored;
    }
  } catch (_) {
    // The demo also works when browser storage is unavailable.
  }
  const save = () => {
    try { sessionStorage.setItem(stateKey, JSON.stringify(state)); } catch (_) { /* Optional persistence. */ }
  };
  document.querySelectorAll("[data-inactive]").forEach(form => {
    form.addEventListener("submit", event => event.preventDefault());
  });
  document.getElementById("start-demo")?.addEventListener("submit", event => {
    event.preventDefault();
    state = { title: document.getElementById("title").value.trim() || "Demo story", exchanges: [] };
    save();
    location.href = "story.html";
  });
  if (document.getElementById("knowledgeGraph")) {
    const data = window.StorytellerDemoData;
    document.getElementById("systemPrompt").value = data.systemPrompt;
    document.getElementById("fixedProtagonists").value = data.fixedProtagonistsYaml;
    document.getElementById("rules").value = data.rules;
    document.getElementById("knowledgeGraph").value = JSON.stringify(data.graph, null, 2);
    document.getElementById("temperature").value = data.temperature;
    document.getElementById("temperatureValue").value = data.temperature.toFixed(2);
  }
  const temperature = document.getElementById("temperature");
  temperature?.addEventListener("input", () => {
    document.getElementById("temperatureValue").value = Number(temperature.value).toFixed(2);
  });

  const conversation = document.getElementById("conversation");
  if (conversation) {
    document.querySelector("h1").textContent = state.title;
    document.title = state.title;
    const undo = document.getElementById("undo-story");
    const render = () => {
      conversation.querySelectorAll(".exchange, .empty-state").forEach(element => element.remove());
      if (!state.exchanges.length) {
        const empty = document.createElement("div");
        empty.className = "empty-state";
        empty.innerHTML = "<h2>Begin the story</h2><p>Describe the opening scene or tell the storyteller what should happen next.</p>";
        conversation.append(empty);
      }
      state.exchanges.forEach((exchange, index) => {
        const article = document.createElement("article");
        article.className = "exchange";
        for (const [role, content] of [["Prompt", exchange.prompt], ["Response", demoReply]]) {
          const column = document.createElement("div");
          column.className = `exchange-${role.toLowerCase()}`;
          const label = document.createElement("p");
          label.className = "message-role";
          label.textContent = role;
          const message = document.createElement("div");
          message.className = "message-content";
          message.textContent = content;
          column.append(label, message);
          if (role === "Prompt" && index < state.exchanges.length - 1) {
            const reference = document.createElement("label");
            reference.className = "past-reference";
            const checkbox = document.createElement("input");
            checkbox.type = "checkbox";
            checkbox.name = "pastExchange";
            reference.append(checkbox, "Use as past context");
            column.insertBefore(reference, message);
          }
          if (role === "Response") {
            const edit = document.createElement("details");
            edit.className = "response-edit";
            const summary = document.createElement("summary");
            summary.textContent = "Edit";
            const editor = document.createElement("textarea");
            editor.setAttribute("aria-label", "Response text");
            editor.value = content;
            editor.readOnly = true;
            const button = document.createElement("button");
            button.type = "button";
            button.className = "compact-button";
            button.textContent = "Save response";
            button.disabled = true;
            button.title = "Demo replies are fixed";
            edit.append(summary, editor, button);
            column.insertBefore(edit, message);
          }
          if (role === "Prompt" && exchange.image) {
            const image = document.createElement("img");
            image.className = "prompt-thumbnail";
            image.alt = "Image supplied with this demo prompt";
            image.src = exchange.image;
            column.append(image);
          }
          article.append(column);
        }
        conversation.append(article);
      });
      undo.disabled = !state.exchanges.length;
      updateReferences();
      conversation.scrollTop = conversation.scrollHeight;
    };
    const updateReferences = () => {
      const references = [...conversation.querySelectorAll('input[name="pastExchange"]')];
      const selected = references.filter(reference => reference.checked).length;
      references.forEach(reference => { reference.disabled = !reference.checked && selected >= 3; });
      document.getElementById("past-reference-count").textContent = `${selected}/3 past exchanges`;
    };
    conversation.addEventListener("change", updateReferences);
    render();
    undo.addEventListener("click", () => { state.exchanges.pop(); save(); render(); });
    const prompt = document.getElementById("prompt");
    const imageInput = document.getElementById("image");
    let attachedImage = null;
    let selection = 0;
    const clearImage = () => {
      selection += 1;
      attachedImage = null;
      imageInput.value = "";
      document.getElementById("image-preview").hidden = true;
      document.getElementById("image-preview-thumbnail").removeAttribute("src");
    };
    imageInput.addEventListener("change", () => {
      const file = imageInput.files[0];
      clearImage();
      if (!file || !["image/png", "image/jpeg"].includes(file.type)) return;
      const currentSelection = selection;
      const reader = new FileReader();
      reader.addEventListener("load", () => {
        if (currentSelection !== selection) return;
        attachedImage = reader.result;
        document.getElementById("image-preview-thumbnail").src = attachedImage;
        document.getElementById("image-preview-name").textContent = file.name;
        document.getElementById("image-preview").hidden = false;
      });
      reader.readAsDataURL(file);
    });
    document.getElementById("remove-image").addEventListener("click", clearImage);
    const composer = document.getElementById("composer");
    composer.addEventListener("submit", event => {
      event.preventDefault();
      if (!prompt.value.trim()) return;
      state.exchanges.push({ prompt: prompt.value, image: attachedImage });
      save();
      render();
      prompt.value = "";
      clearImage();
      prompt.focus();
    });
    prompt.addEventListener("keydown", event => {
      if (event.key === "Enter" && event.shiftKey) {
        event.preventDefault();
        composer.requestSubmit();
      }
    });
    for (const [id, className, active, inactive] of [
      ["response-layout-toggle", "single-column", "Dual", "Single"],
      ["response-width-toggle", "response-maximized", "Normal", "Maximize"]
    ]) {
      document.getElementById(id).addEventListener("click", event => {
        const enabled = document.getElementById("story-page").classList.toggle(className);
        event.currentTarget.setAttribute("aria-pressed", String(enabled));
        event.currentTarget.textContent = enabled ? active : inactive;
      });
    }
  }

  const atlasForm = document.getElementById("atlas-search-form");
  if (atlasForm) {
    const data = window.StorytellerDemoData;
    const graph = data.graph;
    const fieldLabel = value => {
      const text = value.toLowerCase().replaceAll("_", " ");
      return text.charAt(0).toUpperCase() + text.slice(1);
    };
    const entries = Object.entries(graph.entities).map(([id, entity]) => ({
      id, ...entity, source: fieldLabel(entity.source), profile: null
    }));
    // The controlled demo fixture matches profiles by their key or full name.
    for (const [key, profile] of Object.entries(data.protagonists)) {
      const names = [key, profile.full_name].filter(Boolean);
      const entry = entries.find(entry => entry.type === "CHARACTER"
        && [entry.name, ...entry.aliases].some(name => names.includes(name)));
      if (entry) {
        entry.profile = profile;
        entry.aliases = [...new Set([...entry.aliases, ...names])].filter(name => name !== entry.name);
      } else {
        entries.push({ id: `profile:${key}`, type: "CHARACTER", name: key,
          aliases: names.filter(name => name !== key), source: "Fixed protagonists", profile });
      }
    }
    entries.sort((a, b) => a.name.toLowerCase().localeCompare(b.name.toLowerCase()) || a.id.localeCompare(b.id));
    const relations = graph.facts.map(fact => {
      const predicate = data.predicates[fact.predicate];
      const verb = fact.polarity === "NEGATIVE" ? predicate.negativeText : predicate.positiveText;
      return { ...fact, subjectId: fact.subject, objectId: fact.object,
        text: `${graph.entities[fact.subject].name} ${verb} ${graph.entities[fact.object].name}`,
        source: fieldLabel(fact.source) };
    });
    const renderProfile = profile => {
      const list = document.createElement("dl");
      list.className = "atlas-profile";
      for (const [key, value] of Object.entries(profile)) {
        const row = document.createElement("div");
        const label = document.createElement("dt");
        label.textContent = fieldLabel(key);
        const content = document.createElement("dd");
        if (value && typeof value === "object" && !Array.isArray(value)) {
          content.append(renderProfile(value));
        } else {
          const text = document.createElement("span");
          text.className = "atlas-profile-value";
          text.textContent = Array.isArray(value) ? value.join(", ") : value == null ? "Not specified" : String(value);
          content.append(text);
        }
        row.append(label, content);
        list.append(row);
      }
      return list;
    };
    document.querySelector(".atlas-revision").textContent =
      `Graph revision: ${graph.revision} · Only stored demo facts are shown; missing details are not inferred.`;
    const renderFacts = facts => {
      const list = document.createElement("ul");
      list.className = "atlas-facts";
      for (const relation of facts) {
        const fact = document.createElement("li");
        const text = document.createElement("p");
        text.textContent = relation.text;
        const source = document.createElement("p");
        source.className = "setting-hint";
        source.textContent = `${relation.status} · Source: ${relation.source}`;
        const connections = document.createElement("div");
        connections.className = "atlas-connections";
        connections.setAttribute("aria-label", "Connected entries");
        for (const id of new Set([relation.subjectId, relation.objectId])) {
          const entry = entries.find(entry => entry.id === id);
          const link = document.createElement("a");
          link.href = `atlas.html?${new URLSearchParams({ type: entry.type, entry: entry.id })}`;
          link.textContent = entry.name;
          connections.append(link);
        }
        fact.append(text, source, connections);
        list.append(fact);
      }
      return list;
    };
    const params = new URLSearchParams(location.search);
    const search = document.getElementById("atlas-search");
    const category = document.getElementById("atlas-type");
    search.value = (params.get("q") || "").trim();
    const requestedType = params.get("type");
    category.value = [...category.options].some(option => option.value === requestedType)
      ? requestedType : "CHARACTER";
    const matches = entries.filter(entry => entry.type === category.value
      && [entry.name, ...entry.aliases].some(name => name.toLowerCase().includes(search.value.toLowerCase())));
    const selected = matches.find(entry => entry.id === params.get("entry")) || matches[0];
    const list = document.getElementById("atlas-list");
    for (const entry of matches) {
      const link = document.createElement("a");
      link.className = "atlas-entry";
      const query = new URLSearchParams({ q: search.value, type: category.value, entry: entry.id });
      link.href = `atlas.html?${query}`;
      link.textContent = entry.name;
      if (entry === selected) link.setAttribute("aria-current", "true");
      list.append(link);
    }
    const detail = document.getElementById("atlas-detail");
    if (selected) {
      const type = document.createElement("p");
      type.className = "eyebrow";
      type.textContent = selected.type;
      const heading = document.createElement("h2");
      heading.textContent = selected.name;
      const profileHeading = document.createElement("h3");
      profileHeading.textContent = "Profile — Fixed protagonists";
      const factsHeading = document.createElement("h3");
      factsHeading.textContent = "Recorded facts — Knowledge graph";
      const selectedRelations = relations.filter(relation =>
        relation.subjectId === selected.id || relation.objectId === selected.id);
      const active = selectedRelations.filter(relation => relation.status === "ACTIVE");
      const other = selectedRelations.filter(relation => relation.status !== "ACTIVE");
      detail.append(type, heading);
      if (selected.aliases.length) {
        const aliases = document.createElement("p");
        aliases.textContent = `Also known as: ${selected.aliases.join(", ")}`;
        detail.append(aliases);
      }
      const source = document.createElement("p");
      source.className = "setting-hint";
      source.textContent = `Source: ${selected.source}`;
      detail.append(source);
      if (selected.profile) {
        const section = document.createElement("section");
        section.setAttribute("aria-label", "Fixed protagonists profile");
        section.append(profileHeading, renderProfile(selected.profile));
        detail.append(section);
      } else if (selected.type === "CHARACTER") {
        const missing = document.createElement("p");
        missing.className = "setting-hint";
        missing.textContent = "No Fixed protagonists profile is linked to this character.";
        detail.append(missing);
      }
      detail.append(factsHeading);
      if (active.length) {
        detail.append(renderFacts(active));
      } else {
        const empty = document.createElement("p");
        empty.textContent = "No active facts have been recorded for this entry.";
        detail.append(empty);
      }
      if (other.length) {
        const previous = document.createElement("details");
        previous.className = "atlas-other";
        const summary = document.createElement("summary");
        summary.textContent = `Proposed and previous facts (${other.length})`;
        previous.append(summary, renderFacts(other));
        detail.append(previous);
      }
    } else {
      list.textContent = "No entries match this category and search.";
      detail.textContent = "No entry selected. Choose another category or search term.";
    }
    category.addEventListener("change", () => { search.value = ""; atlasForm.requestSubmit(); });
  }
})();
