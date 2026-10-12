// Sends the CSRF token with each htmx request. The layout puts the token in meta tags for signed-in callers.
// Forms send it in a hidden field from the fragment web/csrfField.kte instead.
document.addEventListener("htmx:configRequest", (event) => {
    const header = document.querySelector('meta[name="csrf-header"]');
    const token = document.querySelector('meta[name="csrf-token"]');
    if (header && token) {
        event.detail.headers[header.content] = token.content;
    }
});
