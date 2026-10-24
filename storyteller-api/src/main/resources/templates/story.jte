@import nl.llm.storyteller.api.web.StoryExchange
@import nl.llm.storyteller.api.web.StoryPage

@param StoryPage page

<!doctype html>
<html lang="en">
  <head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>${page.session().title() == null ? "Storyteller" : page.session().title()}</title>
    <script src="/story-theme.js"></script>
    <link rel="stylesheet" href="/storyteller.css">
  </head>
  <body>
    <main id="story-page" class="story-page">
      <header class="story-header">
        <div>
          <p class="eyebrow">Storyteller</p>
          <h1>${page.session().title() == null ? "Untitled story" : page.session().title()}</h1>
          @if(page.session().infinite())
            <p class="resume-session-id">Resume ID: <code>${page.session().sessionId()}</code></p>
          @endif
        </div>
        <div class="session-actions">
          <a id="session-atlas" class="header-action" href="/story/atlas">Atlas</a>
          <a id="session-history" class="header-action session-history" href="/story/memory">History</a>
          <a id="session-settings" class="header-action session-settings" href="/story/settings">Settings</a>
          <form method="post" action="/story/infinite">
            <button id="session-infinite" type="submit" class="infinite-session"
                    aria-pressed="${page.session().infinite()}">
              ${page.session().infinite() ? "Use timeout" : "Infinite"}
            </button>
          </form>
          <a class="header-action export-session" href="/export">Export</a>
          @if(page.session().infinite())
            <form method="post" action="/story/stop">
              <button type="submit" class="stop-story">Stop story</button>
            </form>
          @else
            <form method="post" action="/story/stop"
                  onsubmit="return confirm('Stop this story? The session and all stored story data will be permanently deleted.')">
              <button type="submit" class="stop-story">Stop story</button>
            </form>
          @endif
        </div>
      </header>

      <section id="conversation" class="conversation" aria-live="polite"
               data-has-older="${page.hasOlder()}">
        <div class="conversation-heading">
          <span>Prompt</span>
          <span class="response-heading">
            <span class="response-label">Response</span>
            <span class="heading-actions">
              <button id="response-layout-toggle" type="button" aria-pressed="false">Single</button>
              <button id="response-width-toggle" type="button" aria-pressed="false">Maximize</button>
            </span>
          </span>
        </div>
        @if(page.exchanges().isEmpty())
          <div class="empty-state">
            <h2>Begin the story</h2>
            <p>Describe the opening scene or tell the storyteller what should happen next.</p>
          </div>
        @endif
        @for(StoryExchange exchange : page.exchanges())
          <article class="exchange" data-message-index="${exchange.messageIndex()}">
            <div class="exchange-prompt">
              <p class="message-role">Prompt</p>
              @if(exchange.referenceEligible())
                <label class="past-reference">
                  <input type="checkbox" name="pastExchange" value="${exchange.messageIndex()}" form="composer">
                  Use as past context
                </label>
              @endif
              @if(exchange.hasImage())
                <a class="prompt-image-link" href="/story/images/${exchange.messageIndex()}"
                   aria-label="Show the full image supplied with this prompt">
                  <img class="prompt-thumbnail" src="/story/images/${exchange.messageIndex()}"
                       alt="Image supplied with this prompt" title="Show full image">
                </a>
              @endif
              <div class="message-content">${exchange.prompt()}</div>
            </div>
            <div class="exchange-response">
              <div class="response-title-row">
                <p class="message-role">Response</p>
                <details class="response-edit">
                  <summary>Edit</summary>
                  <form method="post" action="/story/messages/${exchange.messageIndex() + 1}/edit">
                    <label for="response-edit-${exchange.messageIndex()}">Response text</label>
                    <textarea id="response-edit-${exchange.messageIndex()}" name="content" rows="8"
                              maxlength="100000" required>${exchange.response()}</textarea>
                    <button type="submit" class="compact-button">Save response</button>
                  </form>
                </details>
              </div>
              <div class="message-content">${exchange.response()}</div>
            </div>
          </article>
        @endfor
      </section>

      <form id="composer" method="post" action="/story/turns" enctype="multipart/form-data" class="composer">
        <label for="prompt">What happens next?</label>
        <textarea id="prompt" name="prompt" rows="3" maxlength="100000" required autofocus>${page.submittedPrompt()}</textarea>
        <p id="image-error" role="alert" hidden></p>
        <div id="image-preview" class="image-preview" hidden>
          <img id="image-preview-thumbnail" alt="Image ready to send">
          <span id="image-preview-name"></span>
          <button id="remove-image" type="button" class="remove-image">Remove</button>
        </div>
        <div class="composer-actions">
          <label class="image-picker" for="image">Add image</label>
          <input id="image" name="image" type="file" accept="image/png,image/jpeg" hidden>
          <span id="past-reference-count" class="past-reference-count">0/3 past exchanges</span>
          <button id="undo-story" type="submit" formaction="/story/undo" formnovalidate
                  class="undo-story"
                  data-undo-available="${page.exchanges().isEmpty() ? "false" : "true"}">Undo</button>
          <button id="continue-story" type="submit">Continue story</button>
        </div>
      </form>
    </main>
    @if(!page.errorMessage().isBlank())
      <div id="model-backend-notification" class="notification-modal" role="alertdialog" aria-modal="true"
           aria-labelledby="model-backend-notification-title"
           aria-describedby="model-backend-notification-message">
        <div class="notification-modal-content">
          <p id="model-backend-notification-title" class="notification-modal-title">Model temporarily unavailable</p>
          <p id="model-backend-notification-message">${page.errorMessage()}</p>
          <button id="model-backend-notification-close" type="button">Close</button>
        </div>
      </div>
    @endif
    <div id="image-lightbox" class="image-lightbox" role="dialog" aria-modal="true"
         aria-label="Prompt image" hidden>
      <div class="image-lightbox-content">
        <button id="image-lightbox-close" class="image-lightbox-close" type="button"
                aria-label="Close full image">Close</button>
        <img id="image-lightbox-image" alt="Full image supplied with this prompt">
      </div>
    </div>
    <script src="/story-images.js"></script>
    <script>
      const conversation = document.getElementById("conversation");
      const imageLightbox = document.getElementById("image-lightbox");
      const imageLightboxImage = document.getElementById("image-lightbox-image");
      const imageLightboxClose = document.getElementById("image-lightbox-close");
      const backendNotification = document.getElementById("model-backend-notification");
      const backendNotificationClose = document.getElementById("model-backend-notification-close");
      const closeBackendNotification = () => {
        if (backendNotification) {
          backendNotification.hidden = true;
        }
      };
      backendNotificationClose?.addEventListener("click", closeBackendNotification);
      if (backendNotification) {
        window.setTimeout(closeBackendNotification, 8000);
      }
      let openedImageLink = null;
      const closeImageLightbox = () => {
        imageLightbox.hidden = true;
        imageLightboxImage.removeAttribute("src");
        openedImageLink?.focus();
        openedImageLink = null;
      };
      conversation.addEventListener("click", event => {
        const imageLink = event.target.closest(".prompt-image-link");
        if (!imageLink) {
          return;
        }
        event.preventDefault();
        openedImageLink = imageLink;
        imageLightboxImage.src = imageLink.href;
        imageLightbox.hidden = false;
        imageLightboxClose.focus();
      });
      imageLightboxClose.addEventListener("click", closeImageLightbox);
      imageLightbox.addEventListener("click", event => {
        if (event.target === imageLightbox) {
          closeImageLightbox();
        }
      });
      document.addEventListener("keydown", event => {
        if (event.key === "Escape" && backendNotification && !backendNotification.hidden) {
          closeBackendNotification();
          return;
        }
        if (event.key === "Escape" && !imageLightbox.hidden) {
          closeImageLightbox();
        }
      });
      const latestExchange = conversation.querySelector(".exchange:last-child");
      const heading = conversation.querySelector(".conversation-heading");
      const scrollToLatestExchange = () => {
        if (!latestExchange) {
          return;
        }
        const latestTop = latestExchange.getBoundingClientRect().top
          - conversation.getBoundingClientRect().top
          + conversation.scrollTop
          - heading.offsetHeight;
        conversation.scrollTop = latestTop;
      };
      scrollToLatestExchange();

      let loadingHistory = false;
      let hasOlderHistory = conversation.dataset.hasOlder === "true";
      conversation.addEventListener("scroll", async () => {
        if (conversation.scrollTop > 80 || loadingHistory || !hasOlderHistory) {
          return;
        }
        const oldestExchange = conversation.querySelector(".exchange");
        if (!oldestExchange) {
          return;
        }
        loadingHistory = true;
        const previousHeight = conversation.scrollHeight;
        try {
          const response = await fetch("/story/history?before=" + oldestExchange.dataset.messageIndex);
          if (!response.ok) {
            throw new Error("History request failed with HTTP " + response.status);
          }
          const template = document.createElement("template");
          template.innerHTML = await response.text();
          heading.after(template.content);
          updatePastReferences();
          hasOlderHistory = response.headers.get("X-Story-Has-More") === "true";
          conversation.scrollTop += conversation.scrollHeight - previousHeight;
        } finally {
          loadingHistory = false;
        }
      });

      const responseWidthToggle = document.getElementById("response-width-toggle");
      const responseLayoutToggle = document.getElementById("response-layout-toggle");
      const storyPage = document.getElementById("story-page");
      responseLayoutToggle.addEventListener("click", () => {
        const singleColumn = storyPage.classList.toggle("single-column");
        responseLayoutToggle.textContent = singleColumn ? "Dual" : "Single";
        responseLayoutToggle.setAttribute("aria-pressed", singleColumn);
        requestAnimationFrame(scrollToLatestExchange);
      });

      responseWidthToggle.addEventListener("click", () => {
        const maximized = storyPage.classList.toggle("response-maximized");
        responseWidthToggle.textContent = maximized ? "Normal" : "Maximize";
        responseWidthToggle.setAttribute("aria-pressed", maximized);
        requestAnimationFrame(scrollToLatestExchange);
      });

      const composer = document.getElementById("composer");
      const prompt = document.getElementById("prompt");
      const continueButton = document.getElementById("continue-story");
      const undoButton = document.getElementById("undo-story");
      const actionLinks = Array.from(document.querySelectorAll("a.header-action"));
      const actionButtons = Array.from(document.querySelectorAll("button"));
      const imageInput = document.getElementById("image");
      const imagePreview = document.getElementById("image-preview");
      const imagePreviewThumbnail = document.getElementById("image-preview-thumbnail");
      const imagePreviewName = document.getElementById("image-preview-name");
      const removeImage = document.getElementById("remove-image");
      const imagePicker = document.querySelector(".image-picker");
      const imageError = document.getElementById("image-error");
      undoButton.disabled = undoButton.dataset.undoAvailable !== "true";
      let submitting = false;
      actionLinks.forEach(link => link.addEventListener("click", event => {
        if (submitting) {
          event.preventDefault();
        }
      }));

      const updatePastReferences = () => {
        const references = Array.from(document.querySelectorAll('input[name="pastExchange"]'));
        const selected = references.filter(reference => reference.checked);
        references.forEach(reference => {
          reference.disabled = !reference.checked && selected.length >= 3;
        });
        document.getElementById("past-reference-count").textContent = selected.length + "/3 past exchanges";
      };
      conversation.addEventListener("change", event => {
        if (event.target.matches('input[name="pastExchange"]')) {
          updatePastReferences();
        }
      });
      updatePastReferences();

      let imagePreparation = Promise.resolve();
      let imageSelection = 0;
      let imagePreviewUrl = null;
      const clearImage = () => {
        imageInput.value = "";
        if (imagePreviewUrl) URL.revokeObjectURL(imagePreviewUrl);
        imagePreviewUrl = null;
        imagePreviewThumbnail.removeAttribute("src");
        imagePreviewThumbnail.hidden = false;
        imagePreview.hidden = true;
      };
      const showImage = file => {
        const selection = ++imageSelection;
        clearImage();
        imageError.textContent = "";
        imageError.hidden = true;
        if (!file) return;
        imagePreparation = (async () => {
          try {
            const prepared = await StoryImages.prepare(file);
            if (selection !== imageSelection) return;
            const transfer = new DataTransfer();
            transfer.items.add(prepared);
            imageInput.files = transfer.files;
            imagePreviewUrl = URL.createObjectURL(prepared);
            imagePreviewThumbnail.src = imagePreviewUrl;
            imagePreviewName.textContent = (prepared.name || "Pasted image")
              + " (" + Math.ceil(prepared.size / 1024) + " KiB)";
            imagePreview.hidden = false;
          } catch (error) {
            if (selection !== imageSelection) return;
            clearImage();
            imageError.textContent = error.message;
            imageError.hidden = false;
            imagePreviewThumbnail.hidden = true;
            imagePreviewName.textContent = "Image not attached";
            imagePreview.hidden = false;
          }
        })();
      };

      imageInput.addEventListener("change", () => showImage(imageInput.files[0]));
      imagePicker.addEventListener("click", event => {
        if (submitting) {
          event.preventDefault();
        }
      });
      removeImage.addEventListener("click", () => {
        ++imageSelection;
        clearImage();
        imageError.textContent = "";
        imageError.hidden = true;
        prompt.focus();
      });

      prompt.addEventListener("paste", event => {
        const imageItem = Array.from(event.clipboardData?.items || [])
          .find(item => item.kind === "file" && item.type.startsWith("image/"));
        if (!imageItem) {
          return;
        }
        const file = imageItem.getAsFile();
        showImage(file);
        event.preventDefault();
      });

      let imageReadyToSubmit = false;
      composer.addEventListener("submit", event => {
        if (event.submitter === continueButton && !imageError.hidden) {
          event.preventDefault();
          return;
        }
        if (!imageReadyToSubmit && event.submitter === continueButton) {
          event.preventDefault();
          const selection = imageSelection;
          imagePreparation.then(() => window.setTimeout(() => {
            if (selection !== imageSelection || !imageError.hidden || submitting) return;
            imageReadyToSubmit = true;
            composer.requestSubmit(continueButton);
            imageReadyToSubmit = false;
          }, 0));
          return;
        }
        if (submitting) {
          event.preventDefault();
          return;
        }
        submitting = true;
        composer.setAttribute("aria-busy", "true");
        prompt.readOnly = true;
        imagePicker.setAttribute("aria-disabled", "true");
        removeImage.disabled = true;
        actionButtons.forEach(button => button.disabled = true);
        actionLinks.forEach(link => link.setAttribute("aria-disabled", "true"));
        if (event.submitter === continueButton) {
          continueButton.textContent = "Writing…";
        } else {
          undoButton.textContent = "Undoing…";
        }
      });

      prompt.addEventListener("keydown", event => {
        if (event.key === "Enter" && event.shiftKey) {
          event.preventDefault();
          composer.requestSubmit(continueButton);
        }
      });

      document.querySelectorAll(".response-edit").forEach(details => {
        const summary = details.querySelector("summary");
        details.addEventListener("toggle", () => {
          summary.textContent = details.open ? "Cancel" : "Edit";
        });
      });
    </script>
  </body>
</html>
