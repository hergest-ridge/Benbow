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
  document.querySelector("[data-focus]")?.scrollIntoView({ block: "start" }); // a step further down its section

  document.addEventListener("click", (e) => {
    const copy = e.target.closest("[data-copy]");
    if (copy) navigator.clipboard.writeText(copy.dataset.copy).then(() => snack("Скопировано"), () => snack("Не удалось скопировать", true));
    if (e.target.closest("[data-print]")) window.print(); // the code of the copy outside the house, on paper
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

  // Picking a file sends it; a drop zone shows it is busy meanwhile. A size picked is saved as it is picked.
  const send = (input) => {
    const names = [...input.files].map((f) => "«" + f.name + "»").join(", ");
    snack((input.dataset.send || "Шифрую и отправляю %s…").replace("%s", names));
    input.closest("[data-drop]")?.classList.add("busy");
    input.form.requestSubmit();
  };
  document.addEventListener("change", (e) => {
    const input = e.target;
    if (input.matches("input[type=file][data-send]") && input.files.length) send(input);
    if (input.matches("form[data-autosave] select")) {
      input.form.classList.add("saving");
      input.form.requestSubmit();
    }
  });
  // Files dropped on a drop zone go as if picked.
  document.querySelectorAll("[data-drop]").forEach((zone) => {
    const input = zone.querySelector("input[type=file]");
    ["dragenter", "dragover"].forEach((type) => zone.addEventListener(type, (e) => {
      e.preventDefault();
      zone.classList.add("over");
    }));
    ["dragleave", "drop"].forEach((type) => zone.addEventListener(type, () => zone.classList.remove("over")));
    zone.addEventListener("drop", (e) => {
      e.preventDefault();
      if (!e.dataTransfer || !e.dataTransfer.files.length) return;
      input.files = e.dataTransfer.files;
      send(input);
    });
  });

  // The drawer's item of the section on screen (CSS shows it by :target; the first one without an address).
  const items = [...document.querySelectorAll(".nav a")];
  const mark = () => {
    // By the address, not :target, which the browser may set only after this script ran on load.
    const target = location.hash && document.getElementById(decodeURIComponent(location.hash.slice(1)));
    const shown = target && target.classList.contains("page") ? location.hash : items[0] && items[0].getAttribute("href");
    items.forEach((a) => a.classList.toggle("on", a.getAttribute("href") === shown));
    // After the browser has shown the section and jumped to it: the page from its top, conversations at their end.
    setTimeout(() => {
      scrollTo(0, 0);
      document.querySelectorAll(".page .chat").forEach((c) => (c.scrollTop = c.scrollHeight));
    }, 30);
  };
  addEventListener("hashchange", mark);
  addEventListener("load", mark);
  mark();

  // The folder picker: the server lists its own folders (a browser can't tell a page a folder's full path).
  const picker = document.getElementById("folder-picker");
  if (picker) {
    let target = null;
    let hideDrive = false;
    let current = null;
    let parent = null;
    const list = picker.querySelector("[data-folder-list]");
    const where = picker.querySelector("[data-folder-where]");
    const choose = picker.querySelector("[data-folder-choose]");
    const up = picker.querySelector("[data-folder-up]");
    const row = (kind, name, path) => {
      const button = document.getElementById("folder-" + kind).content.firstElementChild.cloneNode(true);
      button.querySelector(".name").textContent = name;
      button.querySelector(".path").textContent = kind === "drive" ? path : "";
      button.addEventListener("click", () => load(path));
      return button;
    };
    const group = (title) => Object.assign(document.createElement("div"), { className: "group", textContent: title });
    const load = async (path) => {
      let data;
      try {
        const response = await fetch("/admin/folders" + (path ? "?path=" + encodeURIComponent(path) : ""), { cache: "no-store" });
        data = await response.json();
      } catch (_) {
        return snack("Не удалось прочитать папки", true);
      }
      current = data.path;
      parent = data.parent;
      where.textContent = current || "Этот компьютер";
      choose.disabled = !current;
      up.disabled = !current;
      list.replaceChildren();
      if (data.error) list.append(Object.assign(document.createElement("div"), { className: "none", textContent: data.error }));
      if (data.drive.length && !hideDrive) {
        list.append(group("Похоже на Google Диск"));
        data.drive.forEach((f) => list.append(row("drive", f.name, f.path)));
      }
      if (data.disks.length) {
        list.append(group("Диски"));
        data.disks.forEach((f) => list.append(row("disk", f.name, f.path)));
      }
      data.folders.forEach((f) => list.append(row("folder", f.name, f.path)));
      if (current && !data.folders.length && !data.error) list.append(Object.assign(document.createElement("div"), { className: "none", textContent: "Здесь нет папок" }));
      list.scrollTop = 0;
    };
    document.addEventListener("click", (e) => {
      const open = e.target.closest("[data-pick-folder]");
      if (!open) return;
      target = document.getElementById(open.dataset.pickFolder);
      hideDrive = open.hasAttribute("data-no-drive"); // the second disk is no place for Drive's folder
      picker.showModal();
      load(target && target.value.trim() ? target.value.trim() : null);
    });
    up.addEventListener("click", () => load(parent));
    picker.querySelector("[data-folder-cancel]").addEventListener("click", () => picker.close());
    choose.addEventListener("click", () => {
      if (target && current) target.value = current;
      picker.close();
      if (target) target.focus();
    });
  }

  const bottom = (root) => root.querySelectorAll(".chat").forEach((c) => (c.scrollTop = c.scrollHeight));

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
    if (document.hidden || document.querySelector("dialog[open]")) return;
    for (const el of document.querySelectorAll("[data-live]")) {
      if (el.offsetParent === null || busy(el)) continue; // another tab, or something is being edited here
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
