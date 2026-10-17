"""MakerWorld через API Bambu (api.bambulab.com — без Cloudflare-заглушки сайта).

Поиск и карточки моделей открыты; скачивание 3MF требует входа в аккаунт Bambu.
Токен (accessToken, живёт ~3 месяца) храним в /data/makerworld.json.
"""
import json
import re
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

API = "https://api.bambulab.com"
UA = "bambu_network_agent/01.10.01.50"


class MakerWorldError(Exception):
    pass


class MakerWorld:
    def __init__(self, state_file: Path):
        self.state_file = state_file
        try:
            self.state = json.loads(state_file.read_text())
        except Exception:
            self.state = {}

    # ---------- HTTP ----------

    def _req(self, method: str, path: str, body: dict | None = None, auth: bool = False, timeout=20):
        headers = {"User-Agent": UA, "Accept": "application/json"}
        data = None
        if body is not None:
            data = json.dumps(body).encode()
            headers["Content-Type"] = "application/json"
        if auth:
            if not self.token:
                raise MakerWorldError("Log in to a Bambu (MakerWorld) account first")
            headers["Authorization"] = f"Bearer {self.token}"
        req = urllib.request.Request(API + path, data=data, method=method, headers=headers)
        try:
            with urllib.request.urlopen(req, timeout=timeout) as r:
                raw = r.read()
        except urllib.error.HTTPError as e:
            raw = e.read()
            try:
                msg = json.loads(raw).get("error") or json.loads(raw).get("message")
            except Exception:
                msg = raw[:200].decode(errors="replace")
            if e.code == 401 and auth:
                raise MakerWorldError("The Bambu token is invalid — log in again")
            raise MakerWorldError(f"MakerWorld HTTP {e.code}: {msg}")
        return json.loads(raw) if raw else {}

    # ---------- аккаунт ----------

    @property
    def token(self) -> str | None:
        return self.state.get("token")

    def _save(self):
        self.state_file.write_text(json.dumps(self.state))

    def status(self) -> dict:
        return {"logged_in": bool(self.token), "account": self.state.get("account")}

    def set_token(self, token: str, account: str | None = None):
        token = token.strip()
        if token.lower().startswith("bearer "):
            token = token[7:].strip()
        self.state = {"token": token, "account": account or self.state.get("account"), "saved": time.time()}
        self._save()

    def logout(self):
        self.state = {}
        self._save()

    def login(self, account: str, password: str = "", code: str = "") -> dict:
        """Вход: пароль → (если Bambu просит) код из письма → токен.
        Возвращает {"ok": True} или {"need_code": True}."""
        if code:
            r = self._req("POST", "/v1/user-service/user/login", {"account": account, "code": code})
        else:
            r = self._req("POST", "/v1/user-service/user/login", {"account": account, "password": password})
        token = r.get("accessToken")
        if token:
            self.set_token(token, account)
            return {"ok": True}
        login_type = r.get("loginType")
        if login_type == "verifyCode":
            self._req("POST", "/v1/user-service/user/sendemail/code", {"email": account, "type": "codeLogin"})
            return {"need_code": True}
        if login_type == "tfa":
            raise MakerWorldError("Two-factor authentication is on: paste the token manually (see README)")
        raise MakerWorldError("Login failed: " + (r.get("message") or json.dumps(r)[:200]))

    # ---------- модели ----------

    @staticmethod
    def parse_design_id(text: str) -> int:
        """ID модели из ссылки makerworld.com/.../models/12345-... или просто числа."""
        m = re.search(r"/models/(\d+)", text) or re.fullmatch(r"\s*(\d+)\s*", text)
        if not m:
            raise MakerWorldError("Not a MakerWorld model link")
        return int(m.group(1))

    @staticmethod
    def parse_instance_id(text: str) -> int | None:
        """profileId из якоря ссылки (#profileId-…) — это profile_id профиля, не id инстанса."""
        m = re.search(r"profileId-(\d+)", text)
        return int(m.group(1)) if m else None

    def search(self, query: str, offset: int = 0, limit: int = 20) -> dict:
        q = urllib.parse.urlencode({"keyword": query, "limit": limit, "offset": offset})
        r = self._req("GET", f"/v1/search-service/select/design2?{q}")
        return {
            "total": r.get("total", 0),
            "hits": [{
                "id": h.get("id"),
                "title": h.get("title"),
                "cover": h.get("cover") or h.get("coverUrl"),
                "author": (h.get("designCreator") or {}).get("name"),
                "downloads": h.get("downloadCount"),
                "likes": h.get("likeCount"),
                "prints": h.get("printCount"),
            } for h in r.get("hits") or []],
        }

    def design(self, design_id: int) -> dict:
        d = self._req("GET", f"/v1/design-service/design/{design_id}")
        if not d.get("id"):
            raise MakerWorldError("Model not found")
        instances = []
        for i in d.get("instances") or []:
            info = (i.get("extention") or {}).get("modelInfo") or {}
            compat = info.get("compatibility") or {}
            instances.append({
                "id": i.get("id"),
                "profile_id": i.get("profileId"),
                "title": i.get("title"),
                "cover": i.get("cover"),
                "materials": i.get("materialCnt") or 1,
                "need_ams": bool(i.get("needAms")),
                "printer": compat.get("devProductName"),
                "nozzle": compat.get("nozzleDiameter"),
                "plates": [{"index": p.get("index"), "thumb": (p.get("thumbnail") or {}).get("url")}
                           for p in info.get("plates") or []],
            })
        return {
            "id": d["id"],
            "title": d.get("title"),
            "cover": d.get("coverUrl"),
            "author": (d.get("designCreator") or {}).get("name"),
            "default_instance": d.get("defaultInstanceId"),
            "instances": instances,
        }

    def download_3mf(self, instance_id: int, dest_dir: Path) -> Path:
        r = self._req("GET", f"/v1/design-service/instance/{instance_id}/f3mf?type=download", auth=True)
        url, name = r.get("url"), r.get("name") or f"makerworld_{instance_id}.3mf"
        if not url:
            raise MakerWorldError("MakerWorld returned no file link: " + json.dumps(r)[:200])
        name = re.sub(r"[^\w.\- ]+", "_", name).strip() or f"makerworld_{instance_id}.3mf"
        if not name.lower().endswith(".3mf"):
            name += ".3mf"
        dest = dest_dir / name
        req = urllib.request.Request(url, headers={"User-Agent": UA})
        with urllib.request.urlopen(req, timeout=120) as resp, open(dest, "wb") as f:
            while chunk := resp.read(1 << 16):
                f.write(chunk)
        return dest
