export type Theme = 'default' | 'dark-sci-fi' | 'nature' | 'ocean';

const cookieName = 'storyteller_theme';

export function readTheme(): Theme {
  try {
    const value = document.cookie.split(';').map(cookie => cookie.trim())
      .find(cookie => cookie.startsWith(`${cookieName}=`))?.slice(cookieName.length + 1);
    return value === 'dark-sci-fi' || value === 'nature' || value === 'ocean' ? value : 'default';
  } catch {
    // Cookie access can be blocked; the default theme still works.
    return 'default';
  }
}

export function applyTheme(theme: Theme): void {
  document.documentElement.dataset.theme = theme;
}

export function saveTheme(theme: Theme): void {
  try {
    document.cookie = `${cookieName}=${theme}; Path=/; Max-Age=31536000; SameSite=Lax`
      + (location.protocol === 'https:' ? '; Secure' : '');
  } catch {
    // Keep the theme usable for this visit when cookies are blocked.
  }
}
