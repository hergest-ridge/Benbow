// The server's admin page (served as a file: the page's CSP allows no inline script).
(() => {
  const snack = (text, error) => {
    let bar = document.querySelector(".snackbar");
    if (!bar) {
      bar = document.createElement("div");
      bar.setAttribute("role", "status");
      document.body.append(bar);
    }
    bar.className = "snackbar" + (error ? " err" : "");
    bar.textContent = text;
    clearTimeout(bar._timer);
    bar._timer = setTimeout(() => bar.classList.add("gone"), error ? 8000 : 3500);
  };

  // A message the server left after a POST: shown once, then gone from the address.
  const left = document.querySelector(".snackbar");
  if (left) left._timer = setTimeout(() => left.classList.add("gone"), left.classList.contains("err") ? 8000 : 3500);
  if (location.search) history.replaceState(null, "", location.pathname + location.hash);

  document.addEventListener("click", (e) => {
    const copy = e.target.closest("[data-copy]");
    if (copy) navigator.clipboard.writeText(copy.dataset.copy).then(() => snack("Скопировано"), () => snack("Не удалось скопировать", true));
  });

  // Questions before what can't be undone, and a busy button while a form goes.
  document.addEventListener("submit", (e) => {
    const form = e.target;
    if (form.dataset.confirm && !confirm(form.dataset.confirm)) return e.preventDefault();
    const button = form.querySelector("button[type=submit], button:not([type])");
    if (button && form.dataset.busy) {
      button.disabled = true;
      button.lastChild.textContent = form.dataset.busy;
    }
  }, true);

  // Picking a file sends it.
  document.addEventListener("change", (e) => {
    const input = e.target;
    if (input.matches("input[type=file][data-send]") && input.files.length) {
      snack("Шифрую и отправляю «" + input.files[0].name + "»…");
      input.form.requestSubmit();
    }
  });

  // The tab of the section in view.
  const tabs = [...document.querySelectorAll(".tabs a")];
  const sections = tabs.map((a) => document.querySelector(a.getAttribute("href"))).filter(Boolean);
  const mark = () => {
    let current = sections[0];
    for (const s of sections) if (s.getBoundingClientRect().top < 160) current = s;
    tabs.forEach((a) => a.classList.toggle("on", current && a.getAttribute("href") === "#" + current.id));
  };
  addEventListener("scroll", mark, { passive: true });
  mark();

  const bottom = (root) => root.querySelectorAll(".chat").forEach((c) => (c.scrollTop = c.scrollHeight));
  bottom(document);

  // Live parts: refreshed every few seconds, unless something is being typed or edited in them.
  // Busy: a form opened by hand, a field with focus, or something typed or picked and not sent yet.
  document.addEventListener("click", (e) => {
    const summary = e.target.closest("details > summary");
    if (summary) summary.parentElement.dataset.touched = "1";
  });
  const busy = (el) =>
    el.querySelector("details[open][data-touched]") ||
    [...el.querySelectorAll("input, textarea")].some(
      (i) => i === document.activeElement || (i.type === "file" ? i.files.length > 0 : i.value !== i.defaultValue),
    );
  setInterval(async () => {
    if (document.hidden) return;
    for (const el of document.querySelectorAll("[data-live]")) {
      if (busy(el)) continue;
      try {
        const response = await fetch("/?part=" + el.dataset.live, { cache: "no-store" });
        if (!response.ok) continue;
        const html = await response.text();
        if (html === el._html) continue;
        const atBottom = [...el.querySelectorAll(".chat")].every((c) => c.scrollTop + c.clientHeight >= c.scrollHeight - 24);
        el.innerHTML = html;
        el._html = html;
        if (atBottom) bottom(el);
      } catch (_) {
        // the server restarts or the page is closing: next time
      }
    }
  }, 4000);
})();
