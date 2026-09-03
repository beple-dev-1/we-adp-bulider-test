(() => {
  const documentRoot = document;

  const previewMarker = "/previews/";
  const previewAt = location.pathname.indexOf(previewMarker);
  if (previewAt >= 0) {
    const previewId = location.pathname.slice(previewAt + previewMarker.length);
    const previewChecks = (documentRoot.body.dataset.dgCheck ?? "")
      .split(/\s+/)
      .filter(Boolean);
    const comparedProperties = [
      "background-color", "color", "border-top-style", "border-top-width", "border-bottom-width",
      "border-top-left-radius", "padding-top", "padding-left", "font-size", "font-weight",
      "box-shadow", "background-image", "opacity", "position", "appearance"
    ];

    const isPainted = (element, style) => {
      if (["IMG", "SVG", "CANVAS", "VIDEO"].includes(element.tagName)) return true;
      if (element.children.length === 0 && element.innerText.trim() !== "") return true;
      if (style.backgroundImage !== "none") return true;
      if (!["rgba(0, 0, 0, 0)", "transparent"].includes(style.backgroundColor)) return true;
      if (parseFloat(style.borderTopWidth) > 0 && style.borderTopStyle !== "none") return true;
      return style.boxShadow !== "none";
    };

    const styleKey = element => {
      const style = getComputedStyle(element);
      const before = getComputedStyle(element, "::before").content;
      const after = getComputedStyle(element, "::after").content;
      return comparedProperties.map(property => style.getPropertyValue(property)).join("|")
        + `|${before}|${after}`;
    };

    const isStyled = () => {
      if (!previewChecks.length) return null;
      let found = 0;
      for (const className of previewChecks) {
        const element = documentRoot.getElementsByClassName(className)[0];
        if (!element?.parentElement) continue;
        found += 1;
        const probe = documentRoot.createElement(element.tagName);
        element.parentElement.append(probe);
        const styled = styleKey(element) !== styleKey(probe);
        probe.remove();
        if (styled) return true;
      }
      return found ? false : null;
    };

    const report = () => {
      let visible = false;
      let bottom = 0;
      documentRoot.body.querySelectorAll("*").forEach(element => {
        const rect = element.getBoundingClientRect();
        if (rect.width < 2 || rect.height < 2) return;
        const style = getComputedStyle(element);
        if (style.visibility === "hidden" || style.display === "none" || Number(style.opacity) === 0) return;
        if (!isPainted(element, style)) return;
        visible = true;
        bottom = Math.max(bottom, rect.bottom + scrollY);
      });
      parent.postMessage({
        we: "dg-preview", id: previewId, h: Math.ceil(bottom + 14), vis: visible, styled: isStyled()
      }, "*");
    };

    addEventListener("load", () => {
      const ready = documentRoot.fonts?.ready ?? Promise.resolve();
      ready.then(() => requestAnimationFrame(() => requestAnimationFrame(report)), report);
      setTimeout(report, 1200);
    });
  }

  const systems = documentRoot.querySelector(".dg-systems");
  if (!systems) return;

  const facets = { "online-pg": ["iksan", "jeju"], portal: ["jeju"], webview: ["iksan", "jeju"] };
  const chosenFacet = {};

  const previewSource = frame => {
    const preview = frame.dataset.pv;
    const system = frame.dataset.sys;
    const options = facets[system];
    if (!options) return `previews/${preview}`;
    const facet = chosenFacet[system] ?? options[0];
    return `previews/${preview.replace(/\.html$/, "")}--${facet}.html`;
  };

  const arm = scope => {
    if (!scope) return;
    scope.querySelectorAll("iframe[data-pv]").forEach(frame => {
      const source = previewSource(frame);
      if (frame.dataset.done === source) return;
      frame.style.height = "72px";
      frame.hidden = false;
      frame.closest(".dg-pv")?.querySelector(".dg-pv-fallback")?.setAttribute("hidden", "");
      frame.src = source;
      frame.dataset.done = source;
    });
    scope.querySelectorAll("iframe[data-src]").forEach(frame => {
      if (frame.dataset.done) return;
      frame.src = frame.dataset.src;
      frame.dataset.done = "1";
    });
  };

  addEventListener("message", event => {
    const data = event.data;
    if (!data || data.we !== "dg-preview") return;
    documentRoot.querySelectorAll(".dg-pv").forEach(box => {
      const expected = box.dataset.pid ?? "";
      const stem = expected.replace(/\.html$/, "");
      if (data.id !== expected && !data.id.startsWith(`${stem}--`)) return;
      const frame = box.querySelector("iframe");
      const fallback = box.querySelector(".dg-pv-fallback");
      if (!data.vis || data.styled === false) {
        if (frame) frame.hidden = true;
        if (fallback) fallback.hidden = false;
        return;
      }
      if (frame) {
        frame.hidden = false;
        frame.style.height = `${Math.max(48, Math.min(data.h, 2000))}px`;
      }
      if (fallback) fallback.hidden = true;
    });
  });

  const activePanel = section => section.querySelector(".dg-tab:not([hidden])") ?? section;
  const showTab = (section, tab) => {
    const button = [...section.querySelectorAll(".dg-tabs button")]
      .find(item => item.dataset.tab === tab);
    if (!button) return false;
    section.querySelectorAll(".dg-tabs button").forEach(item => item.classList.toggle("on", item === button));
    section.querySelectorAll(".dg-tab").forEach(panel => {
      panel.hidden = panel.dataset.tab !== tab;
    });
    arm(activePanel(section));
    return true;
  };
  const showSystem = system => {
    documentRoot.querySelectorAll(".dg-system").forEach(section => {
      section.classList.toggle("on", section.dataset.sys === system);
    });
    documentRoot.querySelectorAll(".dg-systems button").forEach(button => {
      button.classList.toggle("on", button.dataset.sys === system);
    });
    const active = documentRoot.querySelector(".dg-system.on");
    if (active) arm(activePanel(active));
  };

  documentRoot.querySelectorAll(".dg-systems button").forEach(button => {
    button.addEventListener("click", () => showSystem(button.dataset.sys));
  });
  documentRoot.querySelectorAll(".dg-system").forEach(section => {
    section.querySelectorAll(".dg-tabs button").forEach(button => {
      button.addEventListener("click", () => {
        showTab(section, button.dataset.tab);
      });
    });
    section.querySelectorAll(".dg-facets button").forEach(button => {
      button.addEventListener("click", () => {
        chosenFacet[section.dataset.sys] = button.dataset.facet;
        section.querySelectorAll(".dg-facets button").forEach(item => item.classList.toggle("on", item === button));
        arm(activePanel(section));
      });
    });
  });

  const fromHash = () => {
    const found = /^#([^/]+)(?:\/([^/]+))?(?:\/([^/]+))?/.exec(location.hash);
    if (!found) return false;
    const [, system, tab, facet] = found;
    const section = documentRoot.querySelector(`.dg-system[data-sys="${CSS.escape(system)}"]`);
    if (!section) return false;
    if (facet) chosenFacet[system] = facet;
    showSystem(system);
    if (facet) {
      section.querySelectorAll(".dg-facets button").forEach(button => {
        button.classList.toggle("on", button.dataset.facet === facet);
      });
    }
    if (tab) showTab(section, tab);
    return true;
  };

  addEventListener("hashchange", fromHash);
  const first = documentRoot.querySelector(".dg-systems button");
  if (!fromHash() && first) showSystem(first.dataset.sys);
})();
