// Droneboard hero: one swipe glides over the keyboard and resolves into a word; the next words are
// already waiting in the strip. An illustration of the gesture, not the real decoder.
(() => {
  const kb = document.querySelector(".kb");
  if (!kb) return;
  const canvas = kb.querySelector("canvas");
  const rowsEl = kb.querySelector(".kb-rows");
  const word = (kb.dataset.word || "dakujem").split("");
  const reduce = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
  const ctx = canvas.getContext("2d");
  const RED = "#d0584b";
  let pts = [];

  function measure() {
    const box = kb.getBoundingClientRect();
    const dpr = Math.min(window.devicePixelRatio || 1, 2);
    canvas.width = Math.round(box.width * dpr);
    canvas.height = Math.round(box.height * dpr);
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    const anchors = word.map((ch) => {
      const k = rowsEl.querySelector(`[data-k="${ch}"]`);
      if (!k) return null;
      const r = k.getBoundingClientRect();
      return [r.left - box.left + r.width / 2, r.top - box.top + r.height / 2];
    }).filter(Boolean);
    // Catmull-Rom through the key centres
    pts = [];
    const p = [anchors[0], ...anchors, anchors[anchors.length - 1]];
    for (let i = 1; i < p.length - 2; i++) {
      for (let t = 0; t < 1; t += 1 / 24) {
        const t2 = t * t, t3 = t2 * t;
        const f = (a, b, c, d) => 0.5 * (2 * b + (-a + c) * t + (2 * a - 5 * b + 4 * c - d) * t2 + (-a + 3 * b - 3 * c + d) * t3);
        pts.push([f(p[i - 1][0], p[i][0], p[i + 1][0], p[i + 2][0]), f(p[i - 1][1], p[i][1], p[i + 1][1], p[i + 2][1])]);
      }
    }
    pts.push(anchors[anchors.length - 1]);
  }

  function draw(progress) {
    const w = canvas.width, h = canvas.height;
    ctx.clearRect(0, 0, w, h);
    const n = Math.max(2, Math.floor(pts.length * progress));
    ctx.lineCap = "round"; ctx.lineJoin = "round";
    // the trail fades toward its start, like a note decaying behind the finger
    for (let i = 1; i < n; i++) {
      const a = 0.25 + 0.75 * (i / n);
      ctx.strokeStyle = `rgba(208, 88, 75, ${a.toFixed(3)})`;
      ctx.lineWidth = 2.6;
      ctx.beginPath(); ctx.moveTo(pts[i - 1][0], pts[i - 1][1]); ctx.lineTo(pts[i][0], pts[i][1]); ctx.stroke();
    }
    const [x, y] = pts[n - 1];
    ctx.fillStyle = RED;
    ctx.beginPath(); ctx.arc(x, y, 3.4, 0, Math.PI * 2); ctx.fill();
  }

  const ease = (t) => 1 - Math.pow(2, -10 * t);
  function play() {
    kb.classList.remove("done"); kb.classList.add("playing");
    const start = performance.now(), dur = 1700;
    const step = (now) => {
      const t = Math.min(1, (now - start) / dur);
      draw(ease(t));
      if (t < 1) requestAnimationFrame(step);
      else { kb.classList.remove("playing"); kb.classList.add("done"); }
    };
    requestAnimationFrame(step);
  }

  const ready = document.fonts && document.fonts.ready ? document.fonts.ready : Promise.resolve();
  ready.then(() => {
    measure();
    if (reduce) { kb.classList.add("static"); draw(1); return; }
    play();
    const replay = () => { if (kb.classList.contains("done")) play(); };
    kb.addEventListener("pointerenter", replay);
    kb.addEventListener("pointerdown", replay);
  });
  let rt;
  window.addEventListener("resize", () => { clearTimeout(rt); rt = setTimeout(() => { measure(); draw(1); kb.classList.add("done"); }, 120); });
})();
