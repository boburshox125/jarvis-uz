# -*- coding: utf-8 -*-
"""
Jonli Oboy — Qishki Yangi yil, o'zbekona uslub.
Kompyuterda ishlayotgan dasturlar multik qahramonlar bo'lib, xonalarda yuradi:
  CPU, GPU (videokarta), har bir disk (C:, D: ...) va Internet xonasi.
Qahramon qaysi xonada ko'p ishlayotgan bo'lsa, o'sha xonaga boradi.
Chiqish: Esc  yoki  tray o'rniga — oynada sichqonchaning o'ng tugmasi.
"""
import tkinter as tk, random, math, time, os, subprocess, threading, ctypes, sys
import psutil

W_ROOM_MIN = 260
UPDATE_SEC = 2.0
MAX_HEROES_PER_ROOM = 8
IGNORE = {"system idle process", "system", "registry", "svchost.exe", "csrss.exe",
          "smss.exe", "wininit.exe", "services.exe", "lsass.exe", "winlogon.exe",
          "fontdrvhost.exe", "dwm.exe", "conhost.exe", "memory compression",
          "runtimebroker.exe", "sihost.exe", "ctfmon.exe", "dllhost.exe",
          "searchindexer.exe", "wudfhost.exe", "spoolsv.exe", "taskhostw.exe"}

# O'zbekona qishki ranglar
BG_TOP, BG_BOT = "#0b1d3a", "#1d3f72"
GOLD, TURQ, LAZ, RED, WHITE = "#e8b84a", "#2ec4c4", "#1f5fae", "#c8323c", "#f4f8ff"
ROOM_COL = {"CPU": "#2a4f8a", "GPU": "#5a2f7a", "NET": "#1f6b6b"}
HERO_COL = ["#e63946", "#f4a261", "#2a9d8f", "#e9c46a", "#8ecae6", "#ff70a6",
            "#90be6d", "#b388eb", "#ffb703", "#06d6a0"]

FRIENDLY = {"chrome": "Chrome", "msedge": "Edge", "firefox": "Firefox", "telegram": "Telegram",
            "adobe premiere pro": "Premiere", "afterfx": "After Effects", "photoshop": "Photoshop",
            "explorer": "Provodnik", "code": "VS Code", "steam": "Steam", "obs64": "OBS",
            "discord": "Discord", "winrar": "WinRAR", "vlc": "VLC", "spotify": "Spotify",
            "claude": "Claude", "capcut": "CapCut", "davinci": "DaVinci", "resolve": "DaVinci"}
ACTION = {"CPU": "hisoblayapti", "GPU": "render qilyapti", "NET": "yuklab olyapti",
          "DISK": "faylga yozyapti"}


def nice_name(exe):
    base = exe.lower().replace(".exe", "")
    for k, v in FRIENDLY.items():
        if k in base:
            return v
    return exe.replace(".exe", "")[:14].capitalize()


# ---------------- Ma'lumot yig'ish ----------------
class Monitor:
    def __init__(self):
        self.prev_io = {}
        self.prev_t = time.time()
        self.prev_net = psutil.net_io_counters()
        self.data = []          # [(pid, name, room, score, action)]
        self.stats = {}
        self.gpu_pids = {}
        self.ncpu = psutil.cpu_count() or 1

    def gpu_query(self):
        res, util = {}, None
        try:
            flags = 0x08000000 if os.name == "nt" else 0
            out = subprocess.run(["nvidia-smi", "--query-compute-apps=pid,used_memory",
                                  "--format=csv,noheader,nounits"], capture_output=True,
                                 text=True, timeout=3, creationflags=flags).stdout
            for line in out.strip().splitlines():
                p, m = [x.strip() for x in line.split(",")]
                res[int(p)] = float(m)
            u = subprocess.run(["nvidia-smi", "--query-gpu=utilization.gpu", "--format=csv,noheader,nounits"],
                               capture_output=True, text=True, timeout=3, creationflags=flags).stdout
            util = float(u.strip().splitlines()[0])
        except Exception:
            pass
        return res, util

    def net_pids(self):
        s = {}
        try:
            for c in psutil.net_connections("inet"):
                if c.pid and c.status == "ESTABLISHED" and c.raddr:
                    s[c.pid] = s.get(c.pid, 0) + 1
        except Exception:
            pass
        return s

    def update(self):
        now = time.time(); dt = max(now - self.prev_t, 0.5); self.prev_t = now
        gpu, gutil = self.gpu_query()
        netc = self.net_pids()
        n = psutil.net_io_counters()
        down = (n.bytes_recv - self.prev_net.bytes_recv) / dt
        up = (n.bytes_sent - self.prev_net.bytes_sent) / dt
        self.prev_net = n
        disks = {}
        for p in psutil.disk_partitions(all=False):
            try:
                if "cdrom" in p.opts or not p.fstype:
                    continue
                disks[p.device[:2].upper()] = psutil.disk_usage(p.mountpoint).percent
            except Exception:
                pass
        self.stats = {"cpu": psutil.cpu_percent(), "ram": psutil.virtual_memory().percent,
                      "down": down, "up": up, "disks": disks, "gpu": gutil}
        out = []
        for pr in psutil.process_iter(["pid", "name", "exe"]):
            try:
                name = pr.info["name"] or ""
                if name.lower() in IGNORE or pr.info["pid"] == os.getpid():
                    continue
                cpu = pr.cpu_percent(None) / self.ncpu
                io = pr.io_counters()
                tot = io.read_bytes + io.write_bytes
                oth = getattr(io, "other_bytes", 0)
                pd = self.prev_io.get(pr.pid, (tot, oth))
                self.prev_io[pr.pid] = (tot, oth)
                disk_rate = (tot - pd[0]) / dt / 1e6          # MB/s
                net_rate = netc.get(pr.pid, 0) * 0.5 + (oth - pd[1]) / dt / 2e6
                gpu_mem = gpu.get(pr.pid, 0) / 500.0
                drive = (pr.info["exe"] or "C:")[:2].upper()
                scores = {"CPU": cpu / 5, "GPU": gpu_mem, "NET": net_rate,
                          "DISK:" + drive: disk_rate * 2}
                room, sc = max(scores.items(), key=lambda kv: kv[1])
                if sc < 0.15:
                    continue
                act = ACTION["DISK" if room.startswith("DISK") else room]
                if room == "CPU":
                    act += f" {cpu:.0f}%"
                elif room.startswith("DISK"):
                    act += f" {disk_rate:.1f}MB/s"
                out.append((pr.pid, nice_name(name), room, sc, act))
            except (psutil.NoSuchProcess, psutil.AccessDenied, ValueError, AttributeError):
                continue
        # bir xil nomli jarayonlarni birlashtirish (masalan, Chrome ko'p jarayon)
        merged = {}
        for pid, nm, room, sc, act in out:
            if nm not in merged or merged[nm][3] < sc:
                merged[nm] = (pid, nm, room, sc, act)
        self.data = sorted(merged.values(), key=lambda x: -x[3])


# ---------------- Qahramon ----------------
class Hero:
    def __init__(self, name, col):
        self.name, self.col = name, col
        self.x = self.y = self.tx = self.ty = None
        self.action, self.room, self.phase = "", "", random.random() * 6
        self.alive = True

    def draw(self, cv, t):
        x, y = self.x, self.y
        bob = math.sin(t * 6 + self.phase) * 3
        walking = abs(self.tx - x) + abs(self.ty - y) > 3
        leg = math.sin(t * 12 + self.phase) * (6 if walking else 1)
        c = self.col
        # oyoqlar
        cv.create_line(x - 6, y + 18, x - 6 + leg, y + 32, width=4, fill="#222", capstyle="round")
        cv.create_line(x + 6, y + 18, x + 6 - leg, y + 32, width=4, fill="#222", capstyle="round")
        # tana (chopon)
        cv.create_oval(x - 20, y - 10 + bob, x + 20, y + 24 + bob, fill=c, outline="#111", width=2)
        cv.create_line(x, y - 8 + bob, x, y + 22 + bob, fill=GOLD, width=2)
        # bosh
        hy = y - 26 + bob
        cv.create_oval(x - 16, hy - 16, x + 16, hy + 16, fill="#ffdcb4", outline="#111", width=2)
        # ko'zlar
        blink = (int(t * 2 + self.phase) % 7 == 0)
        for ex in (-6, 6):
            if blink:
                cv.create_line(x + ex - 3, hy - 2, x + ex + 3, hy - 2, width=2)
            else:
                cv.create_oval(x + ex - 3, hy - 5, x + ex + 3, hy + 2, fill="white", outline="#111")
                cv.create_oval(x + ex - 1, hy - 3, x + ex + 2, hy + 1, fill="#111")
        cv.create_arc(x - 6, hy + 1, x + 6, hy + 10, start=200, extent=140, style="arc", width=2)
        cv.create_oval(x - 13, hy + 2, x - 8, hy + 6, fill="#ff9aa2", outline="")
        cv.create_oval(x + 8, hy + 2, x + 13, hy + 6, fill="#ff9aa2", outline="")
        # do'ppi (qishki, oq naqshli)
        cv.create_polygon(x - 15, hy - 9, x - 12, hy - 22, x + 12, hy - 22, x + 15, hy - 9,
                          fill="#111", outline=GOLD, width=1)
        for k in (-8, 0, 8):
            cv.create_text(x + k, hy - 15, text="❋", fill=WHITE, font=("Segoe UI", 6))
        # logotip-nishon (dastur nomi bosh harfi) ko'krakda
        cv.create_oval(x - 9, y - 2 + bob, x + 9, y + 16 + bob, fill="white", outline=GOLD, width=2)
        cv.create_text(x, y + 7 + bob, text=self.name[:2].upper(), fill=c,
                       font=("Segoe UI", 8, "bold"))
        # nom yorlig'i
        cv.create_text(x + 1, y + 44, text=self.name, fill="#000", font=("Segoe UI", 9, "bold"))
        cv.create_text(x, y + 43, text=self.name, fill=WHITE, font=("Segoe UI", 9, "bold"))
        # nima qilayotgani (pufakcha)
        if self.action:
            bw = len(self.action) * 3.4 + 10
            by = hy - 40 - bob
            cv.create_rectangle(x - bw, by - 10, x + bw, by + 10, fill="white", outline=c, width=2)
            cv.create_polygon(x - 4, by + 10, x + 4, by + 10, x, by + 17, fill="white", outline=c)
            cv.create_text(x, by, text=self.action, fill="#222", font=("Segoe UI", 8))
        # ish bajarish effekti
        if self.room == "GPU":
            cv.create_text(x + 24, y - 6, text="✨", font=("Segoe UI", 10))
        elif self.room == "NET":
            cv.create_text(x + 24, y - 6 + (t * 30 % 20), text="⬇", fill=TURQ, font=("Segoe UI", 10, "bold"))
        elif self.room.startswith("DISK"):
            cv.create_text(x + 24, y - 6, text="📁", font=("Segoe UI", 10))
        else:
            cv.create_text(x + 24, y - 10, text="⚙", fill=GOLD, font=("Segoe UI", 11 + int(t * 4) % 3))

    def step(self):
        self.x += (self.tx - self.x) * 0.06
        self.y += (self.ty - self.y) * 0.06


# ---------------- Asosiy oyna ----------------
class App:
    def __init__(self):
        self.root = tk.Tk()
        self.root.title("Jonli Oboy")
        self.root.overrideredirect(True)
        self.sw, self.sh = self.root.winfo_screenwidth(), self.root.winfo_screenheight()
        self.root.geometry(f"{self.sw}x{self.sh}+0+0")
        self.cv = tk.Canvas(self.root, width=self.sw, height=self.sh, highlightthickness=0, bg=BG_TOP)
        self.cv.pack()
        self.root.bind("<Escape>", lambda e: self.quit())
        self.root.bind("<Button-3>", lambda e: self.menu(e))
        self.mon = Monitor()
        self.heroes = {}
        self.snow = [[random.uniform(0, self.sw), random.uniform(0, self.sh),
                      random.uniform(1, 3.5), random.uniform(0, 6)] for _ in range(160)]
        self.bg_img = self.make_bg()
        self.rooms = {}
        self.t0 = time.time()
        threading.Thread(target=self.loop_data, daemon=True).start()
        self.root.after(300, self.attach_desktop)
        self.tick()

    def menu(self, e):
        m = tk.Menu(self.root, tearoff=0)
        m.add_command(label="Chiqish", command=self.quit)
        m.tk_popup(e.x_root, e.y_root)

    def quit(self):
        self.root.destroy(); sys.exit(0)

    # Oynani ish stoli orqasiga (ikonkalar ostiga) joylash — haqiqiy oboy
    def attach_desktop(self):
        if os.name != "nt":
            return
        try:
            u = ctypes.windll.user32
            progman = u.FindWindowW("Progman", None)
            res = ctypes.c_ulong()
            u.SendMessageTimeoutW(progman, 0x052C, 0, 0, 0, 1000, ctypes.byref(res))
            worker = ctypes.c_void_p(0)

            @ctypes.WINFUNCTYPE(ctypes.c_bool, ctypes.c_void_p, ctypes.c_void_p)
            def enum(h, l):
                if u.FindWindowExW(h, None, "SHELLDLL_DefView", None):
                    worker.value = u.FindWindowExW(None, h, "WorkerW", None)
                return True
            u.EnumWindows(enum, 0)
            target = worker.value or u.FindWindowExW(progman, None, "WorkerW", None) or progman
            hwnd = u.GetParent(self.root.winfo_id()) or self.root.winfo_id()
            u.SetParent(hwnd, target)
            u.MoveWindow(hwnd, 0, 0, self.sw, self.sh, True)
        except Exception as ex:
            print("Ish stoliga biriktirib bo'lmadi:", ex)

    def make_bg(self):
        img = tk.PhotoImage(width=self.sw, height=self.sh)
        def hx(c): return [int(c[i:i + 2], 16) for i in (1, 3, 5)]
        a, b = hx(BG_TOP), hx(BG_BOT)
        for y in range(0, self.sh, 4):
            k = y / self.sh
            col = "#%02x%02x%02x" % tuple(int(a[i] + (b[i] - a[i]) * k) for i in range(3))
            img.put(col, to=(0, y, self.sw, y + 4))
        return img

    def loop_data(self):
        for p in psutil.process_iter():
            try: p.cpu_percent(None)
            except Exception: pass
        while True:
            try: self.mon.update()
            except Exception as ex: print(ex)
            time.sleep(UPDATE_SEC)

    def layout_rooms(self):
        keys = ["CPU", "GPU"] + ["DISK:" + d for d in sorted(self.mon.stats.get("disks", {"C:": 0}))] + ["NET"]
        top, bottom = 150, self.sh - 110
        n = len(keys)
        cols = n if n <= 4 else math.ceil(n / 2)
        rows = 1 if n <= 4 else 2
        gw = (self.sw - 60) / cols
        gh = (bottom - top) / rows
        self.rooms = {}
        for i, k in enumerate(keys):
            r, c = divmod(i, cols)
            x0 = 30 + c * gw + 10; y0 = top + r * gh + 10
            self.rooms[k] = (x0, y0, x0 + gw - 20, y0 + gh - 20)

    def room_title(self, k):
        s = self.mon.stats
        if k == "CPU": return "🧠 Protsessor", f"{s.get('cpu', 0):.0f}%  •  RAM {s.get('ram', 0):.0f}%"
        if k == "GPU":
            g = s.get("gpu"); return "🎮 Videokarta", (f"{g:.0f}%" if g is not None else "NVIDIA topilmadi")
        if k == "NET":
            return "🌐 Internet", f"⬇ {s.get('down', 0)/1e6:.2f}  ⬆ {s.get('up', 0)/1e6:.2f} MB/s"
        d = k.split(":", 1)[1]
        return f"💾 Disk {d}", f"band: {s.get('disks', {}).get(d, 0):.0f}%"

    def draw_room(self, k, box, t):
        cv = self.cv; x0, y0, x1, y1 = box
        col = ROOM_COL.get(k, "#7a4b1f")
        # ravoq (arka) shaklidagi xona — o'zbek me'morchiligi
        cx = (x0 + x1) / 2; aw = (x1 - x0) / 2
        cv.create_rectangle(x0, y0 + 50, x1, y1, fill=col, outline=GOLD, width=3)
        cv.create_arc(x0, y0, x1, y0 + 100, start=0, extent=180, fill=col, outline=GOLD, width=3)
        cv.create_rectangle(x0 + 2, y0 + 49, x1 - 2, y0 + 53, fill=col, outline="")
        # koshin naqsh hoshiya
        step = 22
        xx = x0 + 10
        while xx < x1 - 10:
            cv.create_polygon(xx, y1 - 12, xx + 8, y1 - 20, xx + 16, y1 - 12, xx + 8, y1 - 4,
                              fill=TURQ, outline=WHITE)
            xx += step
        # gumbaz ustida qor
        cv.create_arc(x0 + 20, y0 - 4, x1 - 20, y0 + 30, start=20, extent=140, style="arc",
                      outline=WHITE, width=5)
        # pol
        cv.create_rectangle(x0 + 8, y1 - 60, x1 - 8, y1 - 24, fill="#3b2a1a", outline="")
        # gilam
        cv.create_rectangle(cx - aw * 0.6, y1 - 56, cx + aw * 0.6, y1 - 28, fill=RED, outline=GOLD, width=2)
        cv.create_text(cx, y1 - 42, text="◆ ◇ ◆ ◇ ◆", fill=GOLD, font=("Segoe UI", 9))
        title, sub = self.room_title(k)
        cv.create_text(cx, y0 + 40, text=title, fill=WHITE, font=("Segoe UI", 15, "bold"))
        cv.create_text(cx, y0 + 64, text=sub, fill=GOLD, font=("Segoe UI", 10, "bold"))
        # bo'sh xona
        if not any(h.room == k for h in self.heroes.values()):
            cv.create_text(cx, (y0 + y1) / 2 + 20, text="💤 hozircha tinch", fill="#cfe", font=("Segoe UI", 10))

    def draw_decor(self, t):
        cv = self.cv; sw = self.sw
        cv.create_image(0, 0, image=self.bg_img, anchor="nw")
        # oy
        cv.create_oval(sw - 160, 30, sw - 80, 110, fill="#fff6d5", outline="")
        cv.create_oval(sw - 140, 22, sw - 70, 100, fill=BG_TOP, outline="")
        # sarlavha
        cv.create_text(sw / 2 + 2, 52, text="Yangi yil muborak! 2027", fill="#000", font=("Georgia", 34, "bold"))
        cv.create_text(sw / 2, 50, text="Yangi yil muborak! 2027", fill=GOLD, font=("Georgia", 34, "bold"))
        cv.create_text(sw / 2, 95, text="✦ Kompyuteringiz shaharchasi ✦  dasturlar qayerda ishlayotganini ko'ring",
                       fill=WHITE, font=("Segoe UI", 12))
        # yuqoridagi naqsh hoshiya
        for i in range(0, sw, 40):
            cv.create_polygon(i, 0, i + 20, 14, i + 40, 0, fill=LAZ, outline=TURQ)
        # chiroqlar gulchambari
        for i in range(0, sw, 50):
            on = (int(t * 3) + i // 50) % 3
            c = [RED, GOLD, TURQ][on]
            cv.create_oval(i + 20, 118, i + 28, 128, fill=c, outline="")
        # pastki qor tepaliklari va archa
        cv.create_oval(-100, self.sh - 90, sw / 2, self.sh + 120, fill=WHITE, outline="")
        cv.create_oval(sw / 3, self.sh - 70, sw + 100, self.sh + 120, fill="#e3edff", outline="")
        for ax in (60, sw - 80):
            by = self.sh - 40
            for j, s in enumerate((50, 40, 30)):
                cv.create_polygon(ax - s, by - j * 30, ax + s, by - j * 30, ax, by - j * 30 - 50,
                                  fill="#1b6b3a", outline=WHITE)
            cv.create_text(ax, by - 140, text="★", fill=GOLD, font=("Segoe UI", 20))
        # vaqt
        cv.create_text(sw / 2, self.sh - 40, text=time.strftime("%H:%M   %d.%m.%Y"),
                       fill=LAZ, font=("Segoe UI", 20, "bold"))

    def sync_heroes(self):
        per_room = {}
        seen = set()
        for pid, name, room, sc, act in self.mon.data:
            if room not in self.rooms:
                room = "DISK:C:" if "DISK:C:" in self.rooms else "CPU"
            per_room.setdefault(room, [])
            if len(per_room[room]) >= MAX_HEROES_PER_ROOM:
                continue
            per_room[room].append(name); seen.add(name)
            h = self.heroes.get(name)
            if not h:
                h = Hero(name, HERO_COL[hash(name) % len(HERO_COL)])
                self.heroes[name] = h
            h.action, h.room = act, room
        for nm in list(self.heroes):
            if nm not in seen:
                del self.heroes[nm]
        for room, names in per_room.items():
            x0, y0, x1, y1 = self.rooms[room]
            n = len(names)
            for i, nm in enumerate(names):
                h = self.heroes[nm]
                cols = min(n, max(1, int((x1 - x0) // 90)))
                r, c = divmod(i, cols)
                tx = x0 + (x1 - x0) * (c + 0.5) / cols
                ty = y0 + 140 + r * 115
                ty = min(ty, y1 - 100)
                if h.x is None:
                    h.x, h.y = tx, self.sh  # pastdan kirib keladi
                h.tx, h.ty = tx, ty

    def tick(self):
        t = time.time() - self.t0
        cv = self.cv
        cv.delete("all")
        self.layout_rooms()
        self.draw_decor(t)
        self.sync_heroes()
        for k, box in self.rooms.items():
            self.draw_room(k, box, t)
        for h in self.heroes.values():
            h.step(); h.draw(cv, t)
        for f in self.snow:
            f[1] += f[2]; f[0] += math.sin(t + f[3]) * 0.6
            if f[1] > self.sh:
                f[1] = -5; f[0] = random.uniform(0, self.sw)
            cv.create_oval(f[0], f[1], f[0] + f[2] * 1.6, f[1] + f[2] * 1.6, fill=WHITE, outline="")
        self.root.after(50, self.tick)


if __name__ == "__main__":
    App().root.mainloop()
