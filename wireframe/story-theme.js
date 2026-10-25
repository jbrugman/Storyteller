(() => {
  const storageKey = "storyteller_wireframe_theme";
  const validTheme = value => value === "dark-sci-fi" || value === "nature" || value === "ocean"
    || value === "passion" || value === "summer" || value === "evening" ? value : "default";
  let theme = "default";
  try {
    const value = localStorage.getItem(storageKey);
    theme = validTheme(value);
  } catch (_) {
    // Storage access can be blocked; the default theme still works.
  }
  document.documentElement.dataset.theme = theme;

  document.addEventListener("DOMContentLoaded", () => {
    const selector = document.getElementById("theme");
    if (!selector) return;
    selector.value = theme;
    selector.addEventListener("change", () => {
      theme = validTheme(selector.value);
      document.documentElement.dataset.theme = theme;
      try {
        localStorage.setItem(storageKey, theme);
      } catch (_) {
        // Keep the theme usable on this page when storage is blocked.
      }
    });
  });
})();
