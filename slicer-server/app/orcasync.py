"""Клиент самописного сервера синхронизации пресетов Orca (orca_sync_server.py на принтере).

Протокол: POST /auth/v1/token?grant_type=password|refresh_token → access_token,
GET /api/v1/sync/pull → {"upserts": [{"id","name","content",...}], ...}.
Refresh-токен храним в /data, чтобы не логиниться паролем при каждом запуске
(каждый вход по паролю добавляет запись в db.json сервера).
"""
import json
import time
import urllib.error
import urllib.request
from pathlib import Path


class OrcaSync:
    def __init__(self, url: str, user: str, password: str, state_file: Path):
        self.url = url.rstrip("/")
        self.user = user
        self.password = password
        self.state_file = state_file
        self.access = None
        self.access_exp = 0.0
        try:
            self.refresh = json.loads(state_file.read_text()).get("refresh_token")
        except Exception:
            self.refresh = None

    @property
    def configured(self) -> bool:
        return bool(self.url and self.user and (self.password or self.refresh))

    def _post(self, path: str, body: dict) -> dict:
        req = urllib.request.Request(
            self.url + path, data=json.dumps(body).encode(), method="POST",
            headers={"Content-Type": "application/json"},
        )
        with urllib.request.urlopen(req, timeout=15) as r:
            return json.loads(r.read())

    def _login(self):
        session = None
        if self.refresh:
            try:
                session = self._post("/auth/v1/token?grant_type=refresh_token", {"refresh_token": self.refresh})
            except urllib.error.HTTPError:
                session = None
        if session is None:
            if not self.password:
                raise RuntimeError("orca-sync: refresh token is invalid and no password is set")
            session = self._post("/auth/v1/token?grant_type=password",
                                 {"username": self.user, "password": self.password})
        self.access = session["access_token"]
        self.access_exp = time.time() + int(session.get("expires_in", 3600)) - 60
        if session.get("refresh_token") and session["refresh_token"] != self.refresh:
            self.refresh = session["refresh_token"]
            self.state_file.write_text(json.dumps({"refresh_token": self.refresh}))

    def pull(self) -> list[dict]:
        """Все актуальные пользовательские пресеты (content)."""
        if not self.access or time.time() > self.access_exp:
            self._login()
        req = urllib.request.Request(self.url + "/api/v1/sync/pull",
                                     headers={"Authorization": f"Bearer {self.access}"})
        try:
            with urllib.request.urlopen(req, timeout=20) as r:
                data = json.loads(r.read())
        except urllib.error.HTTPError as e:
            if e.code != 401:
                raise
            self.access = None
            self._login()
            return self.pull()
        out = []
        for p in data.get("upserts", []):
            content = p.get("content")
            if isinstance(content, dict) and content:
                content = dict(content)
                content.setdefault("name", p.get("name"))
                out.append(content)
        return out
