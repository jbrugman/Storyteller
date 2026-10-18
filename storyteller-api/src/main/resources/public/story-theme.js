(() => {
  const cookieName = "storyteller_theme";
  const validTheme = value => value === "dark-sci-fi" || value === "nature" || value === "ocean" ? value : "default";
  let theme = "default";
  try {
    const value = document.cookie.split(";").map(cookie => cookie.trim())
      .find(cookie => cookie.startsWith(`${cookieName}=`))?.slice(cookieName.length + 1);
    theme = validTheme(value);
  } catch (_) {
    // Cookie access can be blocked; the default theme still works.
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
        document.cookie = `${cookieName}=${theme}; Path=/; Max-Age=31536000; SameSite=Lax`
          + (location.protocol === "https:" ? "; Secure" : "");
      } catch (_) {
        // Keep the theme usable on this page when cookies are blocked.
      }
    });
  });
})();
