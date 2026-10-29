// The loading page wears the theme the app last wore in this browser (ADR-057), else the default palette's in the
// browser's mode. The app keeps `benbow.theme` in localStorage up to date as its theme changes; the defaults between
// the markers are generated from BenbowTheme.kt by ThemeFilesTest.
(function () {
    /* theme:begin */
    var light = { page: '#FBEBDD', accent: '#8F4E00', quiet: '#544437' };
    var night = { page: '#1B1106', accent: '#FFB77A', quiet: '#DAC2B1' };
    /* theme:end */
    var theme = null;
    try { theme = JSON.parse(localStorage.getItem('benbow.theme')); } catch (e) {}
    if (!theme || !theme.page) {
        var dark = window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches;
        theme = dark ? night : light;
        theme.dark = dark;
    }
    var root = document.documentElement;
    root.style.setProperty('--page', theme.page);
    root.style.setProperty('--accent', theme.accent);
    root.style.setProperty('--quiet', theme.quiet);
    root.style.colorScheme = theme.dark ? 'dark' : 'light';
    document.querySelectorAll('meta[name=theme-color]').forEach(function (meta) { meta.setAttribute('content', theme.page); });
})();
